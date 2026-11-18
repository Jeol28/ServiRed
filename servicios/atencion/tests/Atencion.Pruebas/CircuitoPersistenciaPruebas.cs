using System.Net;
using Microsoft.Extensions.DependencyInjection;
using ServiRed.Atencion.Infraestructura.Persistencia;

namespace ServiRed.Atencion.Pruebas;

public class CircuitoPersistenciaPruebas
{
    /// <summary>Responde siempre con el mismo código y cuenta las llamadas que llegan a «Persistencia».</summary>
    private sealed class PersistenciaFalsa(HttpStatusCode codigo) : HttpMessageHandler
    {
        public int Llamadas;

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage peticion, CancellationToken ct)
        {
            Interlocked.Increment(ref Llamadas);
            return Task.FromResult(new HttpResponseMessage(codigo));
        }
    }

    private static (HttpClient, PersistenciaFalsa) Cliente(HttpStatusCode codigo)
    {
        var falsa = new PersistenciaFalsa(codigo);
        var servicios = new ServiceCollection().AddLogging();
        servicios.AddHttpClient("persistencia", h => h.BaseAddress = new Uri("http://persistencia:8080"))
            .ConCircuitoPersistencia(TimeSpan.FromSeconds(5))
            .ConfigurePrimaryHttpMessageHandler(() => falsa);
        var http = servicios.BuildServiceProvider().GetRequiredService<IHttpClientFactory>().CreateClient("persistencia");
        return (http, falsa);
    }

    [Fact]
    public async Task ConPersistenciaCaidaElCircuitoSeAbreYFallaAlInstanteComoFallaTransitoria()
    {
        var (http, persistencia) = Cliente(HttpStatusCode.ServiceUnavailable);
        for (var i = 0; i < 10; i++)
        {
            using var r = await http.GetAsync("/interno/v1/algo");
            Assert.Equal(HttpStatusCode.ServiceUnavailable, r.StatusCode);
        }

        var e = await Assert.ThrowsAsync<HttpRequestException>(() => http.GetAsync("/interno/v1/algo"));

        Assert.Contains("circuito abierto", e.Message);
        Assert.Equal(HttpStatusCode.ServiceUnavailable, e.StatusCode);
        Assert.Equal(10, persistencia.Llamadas);
    }

    [Fact]
    public async Task UnNoEncontradoNoAbreElCircuito()
    {
        var (http, persistencia) = Cliente(HttpStatusCode.NotFound);
        for (var i = 0; i < 15; i++)
        {
            using var r = await http.GetAsync("/interno/v1/algo");
            Assert.Equal(HttpStatusCode.NotFound, r.StatusCode);
        }

        Assert.Equal(15, persistencia.Llamadas);
    }
}
