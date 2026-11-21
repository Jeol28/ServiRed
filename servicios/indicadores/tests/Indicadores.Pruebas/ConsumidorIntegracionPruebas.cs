using System.Text;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using RabbitMQ.Client;
using ServiRed.Indicadores.Aplicacion;
using Testcontainers.RabbitMq;

namespace ServiRed.Indicadores.Pruebas;

/// <summary>El consumidor real alimenta la vista de lectura desde un RabbitMQ real con la topología del compose.</summary>
public class ConsumidorIntegracionPruebas : IAsyncLifetime
{
    private readonly RabbitMqContainer _rabbit = new RabbitMqBuilder("rabbitmq:4.1-management-alpine").Build();
    private readonly VistaFalsa _vista = new();
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
            .ConfigureTestServices(s =>
            {
                s.AddSingleton<IVistaLectura>(_vista);
            }));
        _fabrica.CreateClient();
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

    private Task Publicar(string clave, string cuerpo) =>
        _canal.BasicPublishAsync("servired.eventos", clave, false,
            new BasicProperties { Persistent = true, ContentType = "application/json" }, Encoding.UTF8.GetBytes(cuerpo)).AsTask();

    private static async Task Esperar(Func<bool> condicion)
    {
        var limite = DateTime.UtcNow.AddSeconds(15);
        while (!condicion())
        {
            if (DateTime.UtcNow > limite) throw new TimeoutException();
            await Task.Delay(100);
        }
    }

    [Fact]
    public async Task ProyectaReporteCreadoYCasoActualizado()
    {
        var id = Guid.NewGuid();
        await Publicar("reporte.creado.v1", $$"""
            {"idEvento":"{{Guid.NewGuid()}}","tipo":"reporte.creado","version":1,"fechaOcurrencia":"2026-11-17T10:00:00Z",
             "correlationId":"{{Guid.NewGuid()}}","origen":"reportes","datos":{"idReporte":"{{id}}","tipoServicio":"ENERGIA",
             "ubicacion":{"latitud":4.6,"longitud":-74.1},"codigoMunicipio":"11001","inicioEstimado":"2026-11-17T09:00:00Z",
             "magnitud":"CUADRA","fechaRegistro":"2026-11-17T10:00:00Z"} }
            """);
        await Publicar("caso.actualizado.v1", $$"""
            {"idEvento":"{{Guid.NewGuid()}}","tipo":"caso.actualizado","version":1,"fechaOcurrencia":"2026-11-17T11:00:00Z",
             "correlationId":"{{Guid.NewGuid()}}","origen":"atencion","datos":{"idCaso":"{{Guid.NewGuid()}}","idReporte":"{{id}}",
             "estado":"EN_ATENCION","prioridad":"MEDIA"} }
            """);

        await Esperar(() => _vista.Actualizaciones.Count(a => a.IdReporte == id) == 2);
        Assert.Contains(_vista.Actualizaciones, a => a.IdReporte == id && a.TipoServicio == "ENERGIA");
        Assert.Contains(_vista.Actualizaciones, a => a.IdReporte == id && a.EstadoCaso == "EN_ATENCION");
    }

    [Fact]
    public async Task UnEventoIlegibleVaALaDlq()
    {
        await Publicar("reporte.creado.v1", "no es json");

        var limite = DateTime.UtcNow.AddSeconds(15);
        BasicGetResult? m = null;
        while (m is null && DateTime.UtcNow < limite)
        {
            m = await _canal.BasicGetAsync(TopologiaPrueba.Cola + ".dlq", autoAck: true);
            await Task.Delay(100);
        }
        Assert.NotNull(m);
    }
}
