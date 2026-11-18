using System.Text;
using Prometheus;
using RabbitMQ.Client;
using RabbitMQ.Client.Events;
using ServiRed.Indicadores.Infraestructura.Mensajeria;

namespace ServiRed.Indicadores.Pruebas;

public class SondaColaPruebas
{
    private static BasicDeliverEventArgs Entrega(string routingKey, object? publicadaMs)
    {
        var propiedades = new BasicProperties { Headers = new Dictionary<string, object?>() };
        if (publicadaMs is not null) propiedades.Headers[SondaCola.Cabecera] = publicadaMs;
        return new BasicDeliverEventArgs("c", 1, false, "servired.eventos", routingKey, propiedades,
            Encoding.UTF8.GetBytes("{}"));
    }

    private static async Task<double> EdadAsync(string cola)
    {
        using var flujo = new MemoryStream();
        await Metrics.DefaultRegistry.CollectAndExportAsTextAsync(flujo);
        var linea = Encoding.UTF8.GetString(flujo.ToArray()).Split('\n')
            .Single(l => l.StartsWith("servired_cola_mensaje_mas_viejo_segundos{") && l.Contains($"cola=\"{cola}\""));
        return double.Parse(linea[(linea.LastIndexOf(' ') + 1)..], System.Globalization.CultureInfo.InvariantCulture);
    }

    [Fact]
    public void ReconoceLaSondaYNoLosEventosDeNegocio()
    {
        Assert.True(SondaCola.EsSonda(Entrega("sonda.v1", 1L)));
        Assert.False(SondaCola.EsSonda(Entrega("reporte.creado.v1", null)));
    }

    [Fact]
    public async Task LaEdadDelMasViejoSeAcotaConLaUltimaSondaConsumida()
    {
        var cola = $"prueba-{Guid.NewGuid():N}";
        SondaCola.Registrar(cola);
        var hace3s = DateTimeOffset.UtcNow.AddSeconds(-3).ToUnixTimeMilliseconds();

        var espera = SondaCola.Recibir(cola, Entrega("sonda.v1", hace3s));

        Assert.InRange(espera!.Value, 2.9, 4);
        Assert.InRange(await EdadAsync(cola), 2.9, 4);
        // Una sonda más vieja que llega tarde (otra réplica) no hace retroceder la cota.
        SondaCola.Recibir(cola, Entrega("sonda.v1", hace3s - 20_000));
        Assert.InRange(await EdadAsync(cola), 2.9, 4);
    }

    [Fact]
    public async Task UnaColaAtrasadaAlArrancarSeMideConLaSondaAunqueSeaAnteriorAlArranque()
    {
        var cola = $"prueba-{Guid.NewGuid():N}";
        SondaCola.Registrar(cola);
        var hace1h = DateTimeOffset.UtcNow.AddHours(-1).ToUnixTimeMilliseconds();

        SondaCola.Recibir(cola, Entrega("sonda.v1", hace1h));

        Assert.InRange(await EdadAsync(cola), 3599, 3602);
    }

    [Fact]
    public void UnaSondaSinHoraSeIgnora() =>
        Assert.Null(SondaCola.Recibir("prueba-sin-hora", Entrega("sonda.v1", null)));
}
