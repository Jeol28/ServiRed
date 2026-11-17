using System.Text;
using System.Text.Json;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Prometheus;
using RabbitMQ.Client;
using RabbitMQ.Client.Events;
using ServiRed.Indicadores.Aplicacion;
using ServiRed.Indicadores.Dominio;
using ServiRed.Indicadores.Infraestructura.Persistencia;
using ServiRed.Indicadores.Infraestructura.RabbitMq;

namespace ServiRed.Indicadores.Infraestructura.Mensajeria;

/// <summary>
/// Consume los eventos que alimentan la vista de lectura (ADR-10) con confirmación manual:
/// <list type="bullet">
/// <item>Éxito → ack.</item>
/// <item>Falla transitoria → se publica una copia en la cola .reintento (vuelve tras
/// el TTL) con x-intentos + 1 y se hace ack; al llegar al máximo → DLQ.</item>
/// <item>Falla permanente o mensaje ilegible → nack sin reencolar → DLQ.</item>
/// <item>Tipos o versiones que Atención no maneja → ack (se ignoran).</item>
/// </list>
/// La proyección es idempotente: reaplicar un evento no cambia la vista.
/// </summary>
public sealed class ConsumidorEventos(
    ConexionRabbitMq conexion,
    OpcionesConsumidor opciones,
    IServiceScopeFactory alcances,
    EstadoConsumidor estado,
    ILogger<ConsumidorEventos> log) : BackgroundService
{
    public const string CabeceraIntentos = "x-intentos";

    private static readonly Counter Procesados = Metrics.CreateCounter(
        "servired_eventos_procesados_total", "Eventos consumidos por resultado",
        new CounterConfiguration { LabelNames = ["tipo", "resultado"] });

    private IChannel? _publicacion;

    protected override async Task ExecuteAsync(CancellationToken detener)
    {
        while (!detener.IsCancellationRequested)
        {
            try
            {
                await ConsumirHastaCaerAsync(detener);
            }
            catch (OperationCanceledException) when (detener.IsCancellationRequested)
            {
                return;
            }
            catch (Exception e)
            {
                log.LogWarning("consumidor de {Cola} detenido, se reconecta en 5 s: {Error}", opciones.Cola, e.Message);
            }
            estado.Activo = false;
            await Task.Delay(TimeSpan.FromSeconds(5), detener).ContinueWith(_ => { }, TaskScheduler.Default);
        }
    }

    private async Task ConsumirHastaCaerAsync(CancellationToken detener)
    {
        var c = await conexion.ObtenerAsync(detener);
        await using var canal = await c.CreateChannelAsync(cancellationToken: detener);
        await using var publicacion = await c.CreateChannelAsync(
            new CreateChannelOptions(publisherConfirmationsEnabled: true, publisherConfirmationTrackingEnabled: true),
            detener);
        _publicacion = publicacion;

        var caido = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        canal.ChannelShutdownAsync += (_, _) => { caido.TrySetResult(); return Task.CompletedTask; };

        await canal.BasicQosAsync(0, opciones.Prefetch, false, detener);
        var consumidor = new AsyncEventingBasicConsumer(canal);
        consumidor.ReceivedAsync += (_, entrega) => ProcesarAsync(canal, entrega, detener);
        await canal.BasicConsumeAsync(opciones.Cola, autoAck: false, consumidor, detener);

        estado.Activo = true;
        log.LogInformation("consumiendo {Cola}", opciones.Cola);
        await caido.Task.WaitAsync(detener);
    }

    private async Task ProcesarAsync(IChannel canal, BasicDeliverEventArgs entrega, CancellationToken ct)
    {
        SobreEvento? sobre;
        try
        {
            sobre = JsonSerializer.Deserialize<SobreEvento>(entrega.Body.Span, ClienteVistaLectura.Json);
            if (sobre is null || sobre.IdEvento == Guid.Empty || sobre.CorrelationId == Guid.Empty)
                throw new JsonException("sobre incompleto");
        }
        catch (JsonException e)
        {
            log.LogWarning("mensaje ilegible en {Cola}, va a la DLQ: {Error}", opciones.Cola, e.Message);
            Procesados.WithLabels("desconocido", "dlq").Inc();
            await canal.BasicNackAsync(entrega.DeliveryTag, false, requeue: false, ct);
            return;
        }

        var correlacion = IdCorrelacion.DeEvento(sobre.CorrelationId);
        using var _ = log.BeginScope(new Dictionary<string, object>
        {
            ["correlationId"] = correlacion.Valor,
            ["idEvento"] = sobre.IdEvento,
            ["tipoEvento"] = $"{sobre.Tipo}.v{sobre.Version}",
        });

        try
        {
            var manejado = await DespacharAsync(sobre, correlacion, ct);
            await canal.BasicAckAsync(entrega.DeliveryTag, false, ct);
            Procesados.WithLabels(sobre.Tipo, manejado ? "ok" : "ignorado").Inc();
        }
        catch (FallaPermanente e)
        {
            log.LogWarning("falla permanente, va a la DLQ: {Error}", e.Message);
            Procesados.WithLabels(sobre.Tipo, "dlq").Inc();
            await canal.BasicNackAsync(entrega.DeliveryTag, false, requeue: false, ct);
        }
        catch (Exception e) when (!ct.IsCancellationRequested)
        {
            await ReintentarOEnviarADlqAsync(canal, entrega, sobre, e, ct);
        }
    }

    private async Task<bool> DespacharAsync(SobreEvento sobre, IdCorrelacion correlacion, CancellationToken ct)
    {
        if (sobre.Version != 1 || !ProyeccionEventos.Tipos.Contains(sobre.Tipo))
        {
            log.LogDebug("evento no usado por Indicadores");
            return false;
        }
        await using var alcance = alcances.CreateAsyncScope();
        var proyeccion = alcance.ServiceProvider.GetRequiredService<ProyeccionEventos>();
        return await proyeccion.AplicarAsync(sobre.Tipo, sobre.FechaOcurrencia, sobre.Datos, correlacion, ct);
    }

    private async Task ReintentarOEnviarADlqAsync(IChannel canal, BasicDeliverEventArgs entrega, SobreEvento sobre,
        Exception error, CancellationToken ct)
    {
        var intentos = LeerIntentos(entrega.BasicProperties) + 1;
        if (intentos >= opciones.MaxIntentos || _publicacion is null)
        {
            log.LogError("falla transitoria tras {Intentos} intentos, va a la DLQ: {Error}", intentos, error.Message);
            Procesados.WithLabels(sobre.Tipo, "dlq").Inc();
            await canal.BasicNackAsync(entrega.DeliveryTag, false, requeue: false, ct);
            return;
        }

        log.LogWarning("falla transitoria (intento {Intentos}/{Max}), se reintenta: {Error}",
            intentos, opciones.MaxIntentos, error.Message);
        var propiedades = new BasicProperties(entrega.BasicProperties)
        {
            Persistent = true,
            Headers = new Dictionary<string, object?>(entrega.BasicProperties.Headers ?? new Dictionary<string, object?>())
            {
                [CabeceraIntentos] = intentos,
            },
        };
        try
        {
            await _publicacion.BasicPublishAsync("", opciones.ColaReintento, mandatory: true, propiedades,
                entrega.Body, ct);
            await canal.BasicAckAsync(entrega.DeliveryTag, false, ct);
            Procesados.WithLabels(sobre.Tipo, "reintento").Inc();
        }
        catch (Exception e)
        {
            log.LogError("no se pudo programar el reintento, se reencola: {Error}", e.Message);
            await canal.BasicNackAsync(entrega.DeliveryTag, false, requeue: true, ct);
        }
    }

    internal static int LeerIntentos(IReadOnlyBasicProperties propiedades)
    {
        if (propiedades.Headers is null || !propiedades.Headers.TryGetValue(CabeceraIntentos, out var valor))
            return 0;
        return valor switch
        {
            int i => i,
            long l => (int)l,
            byte[] b when int.TryParse(Encoding.UTF8.GetString(b), out var i) => i,
            _ => 0,
        };
    }
}

/// <summary>Estado del consumidor para el readiness.</summary>
public sealed class EstadoConsumidor
{
    public volatile bool Activo;
}
