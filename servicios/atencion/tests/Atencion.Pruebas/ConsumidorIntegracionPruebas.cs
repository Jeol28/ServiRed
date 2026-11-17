using System.Text;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using RabbitMQ.Client;
using ServiRed.Atencion.Aplicacion.Puertos;
using ServiRed.Atencion.Dominio;
using Testcontainers.RabbitMq;

namespace ServiRed.Atencion.Pruebas;

/// <summary>
/// El consumidor real contra un RabbitMQ real (Testcontainers) con la topología
/// del compose: éxito, reintento con TTL, DLQ por falla permanente, por mensaje
/// ilegible y por agotar los intentos.
/// </summary>
public class ConsumidorIntegracionPruebas : IAsyncLifetime
{
    private readonly RabbitMqContainer _rabbit = new RabbitMqBuilder("rabbitmq:4.1-management-alpine").Build();
    private readonly PersistenciaFalsa _repositorio = new();
    private WebApplicationFactory<Program> _fabrica = null!;
    private IConnection _conexion = null!;
    private IChannel _canal = null!;

    public async Task InitializeAsync()
    {
        await _rabbit.StartAsync();
        await TopologiaPrueba.DeclararAsync(_rabbit.GetConnectionString());

        var uri = new Uri(_rabbit.GetConnectionString());
        var credenciales = uri.UserInfo.Split(':');
        _fabrica = new WebApplicationFactory<Program>().WithWebHostBuilder(b => b
            .UseSetting("RABBITMQ_HOST", uri.Host)
            .UseSetting("RABBITMQ_PUERTO", uri.Port.ToString())
            .UseSetting("RABBITMQ_USUARIO", credenciales[0])
            .UseSetting("RABBITMQ_CLAVE", credenciales[1])
            .UseSetting("CONSUMIDOR_MAX_INTENTOS", "3")
            .ConfigureTestServices(s =>
            {
                s.AddSingleton<IRepositorioCasos>(_repositorio);
                s.AddSingleton<ICatalogoAtencion>(_repositorio);
                s.AddSingleton<IRepositorioModeracion>(_repositorio);
            }));
        _fabrica.CreateClient(); // arranca el host y el consumidor

        _conexion = await new ConnectionFactory { Uri = uri }.CreateConnectionAsync();
        _canal = await _conexion.CreateChannelAsync();
    }

    public async Task DisposeAsync()
    {
        await _canal.DisposeAsync();
        await _conexion.DisposeAsync();
        await _fabrica.DisposeAsync();
        await _rabbit.DisposeAsync();
    }

    private static string Sobre(Guid idReporte, Guid correlacion, string municipio = "11001") => $$"""
        {"idEvento":"{{Guid.NewGuid()}}","tipo":"reporte.creado","version":1,
         "fechaOcurrencia":"2026-11-14T15:00:00Z","correlationId":"{{correlacion}}","origen":"reportes",
         "datos":{"idReporte":"{{idReporte}}","tipoServicio":"ENERGIA",
                  "ubicacion":{"latitud":4.6287,"longitud":-74.0648},"codigoMunicipio":"{{municipio}}",
                  "inicioEstimado":"2026-11-14T14:00:00Z","magnitud":"CUADRA",
                  "fechaRegistro":"2026-11-14T15:00:00Z","tieneEvidencia":false} }
        """;

    private async Task PublicarAsync(string cuerpo, string clave = "reporte.creado.v1") =>
        await _canal.BasicPublishAsync("servired.eventos", clave, mandatory: false,
            new BasicProperties { Persistent = true, ContentType = "application/json" }, Encoding.UTF8.GetBytes(cuerpo));

    private static async Task EsperarAsync(Func<bool> condicion, int segundos = 15)
    {
        var limite = DateTime.UtcNow.AddSeconds(segundos);
        while (!condicion())
        {
            if (DateTime.UtcNow > limite) throw new TimeoutException("La condición no se cumplió a tiempo");
            await Task.Delay(100);
        }
    }

    private async Task<string?> TomarDeDlqAsync(int segundos = 15)
    {
        var limite = DateTime.UtcNow.AddSeconds(segundos);
        while (DateTime.UtcNow < limite)
        {
            var mensaje = await _canal.BasicGetAsync(TopologiaPrueba.Cola + ".dlq", autoAck: true);
            if (mensaje is not null) return Encoding.UTF8.GetString(mensaje.Body.Span);
            await Task.Delay(100);
        }
        return null;
    }

    [Fact]
    public async Task AbreUnCasoAlRecibirReporteCreadoConLaCorrelacionDelEvento()
    {
        var idReporte = Guid.NewGuid();
        var correlacion = Guid.NewGuid();

        await PublicarAsync(Sobre(idReporte, correlacion));

        await EsperarAsync(() => _repositorio.Casos.Values.Any(c => c.IdReporte == idReporte));
        Assert.Contains(correlacion.ToString(), _repositorio.Correlaciones);
    }

    [Fact]
    public async Task ReintentaLasFallasTransitoriasHastaLograrlo()
    {
        var idReporte = Guid.NewGuid();
        _repositorio.FallasTransitorias[idReporte] = 2;

        await PublicarAsync(Sobre(idReporte, Guid.NewGuid()));

        await EsperarAsync(() => _repositorio.Casos.Values.Any(c => c.IdReporte == idReporte));
    }

    [Fact]
    public async Task TrasAgotarLosIntentosVaALaDlq()
    {
        var idReporte = Guid.NewGuid();
        _repositorio.FallasTransitorias[idReporte] = 100;

        await PublicarAsync(Sobre(idReporte, Guid.NewGuid()));

        var enDlq = await TomarDeDlqAsync();
        Assert.NotNull(enDlq);
        Assert.Contains(idReporte.ToString(), enDlq);
        Assert.DoesNotContain(_repositorio.Casos.Values, c => c.IdReporte == idReporte);
    }

    [Fact]
    public async Task UnaFallaPermanenteVaDirectoALaDlq()
    {
        var idReporte = Guid.NewGuid();
        _repositorio.RechazosPermanentes.Add(idReporte);

        await PublicarAsync(Sobre(idReporte, Guid.NewGuid()));

        Assert.Contains(idReporte.ToString(), await TomarDeDlqAsync());
    }

    [Fact]
    public async Task UnRestablecimientoResuelveElCasoAbierto()
    {
        var idReporte = Guid.NewGuid();
        await PublicarAsync(Sobre(idReporte, Guid.NewGuid()));
        await EsperarAsync(() => _repositorio.Casos.Values.Any(c => c.IdReporte == idReporte));

        await PublicarAsync($$"""
            {"idEvento":"{{Guid.NewGuid()}}","tipo":"restablecimiento.registrado","version":1,
             "fechaOcurrencia":"2026-11-17T15:00:00Z","correlationId":"{{Guid.NewGuid()}}","origen":"reportes",
             "datos":{"idReporte":"{{idReporte}}","fechaRestablecimiento":"2026-11-17T15:00:00Z","duracionMinutos":45} }
            """, "restablecimiento.registrado.v1");

        await EsperarAsync(() => _repositorio.Casos.Values.Any(c => c.IdReporte == idReporte && c.Estado == EstadoCaso.RESUELTO));
        Assert.Contains(_repositorio.Eventos, e => e.Tipo == "caso.actualizado");
    }

    [Fact]
    public async Task UnMensajeIlegibleVaALaDlq()
    {
        await PublicarAsync("esto no es json");

        Assert.Equal("esto no es json", await TomarDeDlqAsync());
    }
}
