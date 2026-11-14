using System.Net;
using Microsoft.AspNetCore.Mvc.Testing;
using ServiRed.Atencion.Dominio;
using Testcontainers.RabbitMq;

namespace ServiRed.Atencion.Pruebas;

/// <summary>Levanta el servicio completo contra un RabbitMQ real (Testcontainers).</summary>
public class SaludIntegracionPruebas : IAsyncLifetime
{
    private readonly RabbitMqContainer _rabbit = new RabbitMqBuilder("rabbitmq:4.1-management-alpine").Build();

    private WebApplicationFactory<Program> _fabrica = null!;
    private HttpClient _cliente = null!;

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
            .UseSetting("RABBITMQ_CLAVE", credenciales[1]));
        _cliente = _fabrica.CreateClient();
    }

    public async Task DisposeAsync()
    {
        await _fabrica.DisposeAsync();
        await _rabbit.DisposeAsync();
    }

    [Fact]
    public async Task LivenessResponde200()
    {
        var respuesta = await _cliente.GetAsync("/health/live");

        Assert.Equal(HttpStatusCode.OK, respuesta.StatusCode);
    }

    [Fact]
    public async Task ReadinessEstaHealthyCuandoConsumeSuCola()
    {
        // El consumidor se conecta en segundo plano: readiness pasa a Healthy cuando consume.
        HttpResponseMessage respuesta;
        var limite = DateTime.UtcNow.AddSeconds(15);
        do
        {
            respuesta = await _cliente.GetAsync("/health/ready");
            if (respuesta.StatusCode == HttpStatusCode.OK) break;
            await Task.Delay(200);
        } while (DateTime.UtcNow < limite);

        Assert.Equal(HttpStatusCode.OK, respuesta.StatusCode);
        Assert.Equal("Healthy", await respuesta.Content.ReadAsStringAsync());
    }

    [Fact]
    public async Task ExponeMetricasPrometheus()
    {
        var respuesta = await _cliente.GetAsync("/metrics");

        Assert.Equal(HttpStatusCode.OK, respuesta.StatusCode);
        Assert.Contains("http_requests_received_total", await respuesta.Content.ReadAsStringAsync());
    }

    [Fact]
    public async Task PropagaElIdDeCorrelacion()
    {
        var id = Guid.NewGuid().ToString();
        using var peticion = new HttpRequestMessage(HttpMethod.Get, "/health/live");
        peticion.Headers.Add(IdCorrelacion.Cabecera, id);

        var respuesta = await _cliente.SendAsync(peticion);

        Assert.Equal(id, respuesta.Headers.GetValues(IdCorrelacion.Cabecera).Single());
    }
}
