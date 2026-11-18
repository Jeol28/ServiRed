using System.Net;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Http.Resilience;
using Microsoft.Extensions.Logging;
using Polly;
using Polly.CircuitBreaker;
using Polly.Timeout;
using Prometheus;

namespace ServiRed.Indicadores.Infraestructura.Persistencia;

/// <summary>
/// Circuit breaker hacia Persistencia (ADR-20). Se abre si al menos la mitad de las
/// llamadas de los últimos 30 s falla (con un mínimo de 10); abierto, cada llamada
/// falla al instante durante 15 s y luego deja pasar una de prueba. Cuentan como
/// falla la red caída, el timeout y los 5xx; un 4xx no.
/// <para>
/// Con el circuito abierto o ante un timeout se lanza <see cref="HttpRequestException"/>
/// (503): la API responde «Servicio no disponible» y los consumidores reprograman el
/// mensaje como cualquier falla transitoria, en vez de enviarlo a la cola de fallidos.
/// </para>
/// </summary>
public static class CircuitoPersistencia
{
    public const string Nombre = "persistencia";

    /// <summary>0 cerrado, 0.5 semiabierto, 1 abierto.</summary>
    private static readonly Gauge Estado = Metrics.CreateGauge("servired_circuito_persistencia_estado",
        "Estado del circuit breaker hacia Persistencia: 0 cerrado, 0.5 semiabierto, 1 abierto");

    public static IHttpClientBuilder ConCircuitoPersistencia(this IHttpClientBuilder cliente, TimeSpan timeout)
    {
        cliente.ConfigureHttpClient(http => http.Timeout = timeout + TimeSpan.FromSeconds(5));
        // Las conexiones se renuevan cada minuto: con varias réplicas de Persistencia la
        // carga se reparte y una réplica retirada deja de recibir tráfico.
        cliente.ConfigurePrimaryHttpMessageHandler(() => new SocketsHttpHandler
        {
            PooledConnectionLifetime = TimeSpan.FromMinutes(1),
            ConnectTimeout = TimeSpan.FromSeconds(2),
        });
        cliente.AddHttpMessageHandler(() => new TraduccionDeFallas());
        cliente.AddResilienceHandler(Nombre, (pipeline, contexto) =>
        {
            var log = contexto.ServiceProvider.GetRequiredService<ILoggerFactory>().CreateLogger(typeof(CircuitoPersistencia));
            pipeline.AddCircuitBreaker(new HttpCircuitBreakerStrategyOptions
            {
                FailureRatio = 0.5,
                MinimumThroughput = 10,
                SamplingDuration = TimeSpan.FromSeconds(30),
                BreakDuration = TimeSpan.FromSeconds(15),
                ShouldHandle = args => ValueTask.FromResult(EsFalla(args.Outcome)),
                OnOpened = args =>
                {
                    Estado.Set(1);
                    log.LogError("circuito hacia persistencia ABIERTO: se falla rápido por {Duracion}", args.BreakDuration);
                    return ValueTask.CompletedTask;
                },
                OnHalfOpened = _ =>
                {
                    Estado.Set(0.5);
                    log.LogWarning("circuito hacia persistencia SEMIABIERTO: se prueba con una llamada");
                    return ValueTask.CompletedTask;
                },
                OnClosed = _ =>
                {
                    Estado.Set(0);
                    log.LogWarning("circuito hacia persistencia CERRADO");
                    return ValueTask.CompletedTask;
                },
            });
            // Dentro del circuito: un timeout cuenta como falla (una cancelación del HttpClient no).
            pipeline.AddTimeout(timeout);
        });
        Estado.Set(0);
        return cliente;
    }

    internal static bool EsFalla(Outcome<HttpResponseMessage> resultado) =>
        resultado.Exception is HttpRequestException or TimeoutRejectedException
        || resultado.Result is { StatusCode: >= HttpStatusCode.InternalServerError };

    /// <summary>Convierte «circuito abierto» y «timeout» en la falla transitoria de siempre.</summary>
    internal sealed class TraduccionDeFallas : DelegatingHandler
    {
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage peticion, CancellationToken ct)
        {
            try
            {
                return await base.SendAsync(peticion, ct);
            }
            catch (BrokenCircuitException e)
            {
                throw new HttpRequestException("Persistencia no disponible (circuito abierto)", e,
                    HttpStatusCode.ServiceUnavailable);
            }
            catch (TimeoutRejectedException e)
            {
                throw new HttpRequestException("Persistencia no respondió a tiempo", e, HttpStatusCode.ServiceUnavailable);
            }
        }
    }
}
