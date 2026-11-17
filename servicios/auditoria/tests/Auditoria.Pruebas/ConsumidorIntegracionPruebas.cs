using System.Text;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using RabbitMQ.Client;
using ServiRed.Auditoria.Aplicacion;
using Testcontainers.RabbitMq;

namespace ServiRed.Auditoria.Pruebas;

/// <summary>El consumidor real registra cualquier evento publicado en servired.eventos (binding "#").</summary>
public class ConsumidorIntegracionPruebas : IAsyncLifetime
{
    private readonly RabbitMqContainer _rabbit = new RabbitMqBuilder("rabbitmq:4.1-management-alpine").Build();
    private readonly RepositorioFalso _repo = new();
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
            .UseSetting("RABBITMQ_HOST", uri.Host).UseSetting("RABBITMQ_PUERTO", uri.Port.ToString())
            .UseSetting("RABBITMQ_USUARIO", credenciales[0]).UseSetting("RABBITMQ_CLAVE", credenciales[1])
            .ConfigureTestServices(s => s.AddSingleton<IRepositorioAuditoria>(_repo)));
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

    [Fact]
    public async Task RegistraLosEventosDeCualquierTipo()
    {
        var idEvento = Guid.NewGuid();
        await _canal.BasicPublishAsync("servired.eventos", "accion.auditada.v1", false,
            new BasicProperties { Persistent = true }, Encoding.UTF8.GetBytes($$"""
                {"idEvento":"{{idEvento}}","tipo":"accion.auditada","version":1,"fechaOcurrencia":"2026-11-17T10:00:00Z",
                 "correlationId":"{{Guid.NewGuid()}}","origen":"indicadores","datos":{"categoria":"EXPORTACION",
                 "accion":"EXPORTACION_REGULATORIA","resultado":"EXITO"} }
                """));

        var limite = DateTime.UtcNow.AddSeconds(15);
        while (!_repo.Registros.ContainsKey(idEvento) && DateTime.UtcNow < limite) await Task.Delay(100);
        Assert.Equal("EXPORTACION", _repo.Registros[idEvento].Categoria);
    }
}
