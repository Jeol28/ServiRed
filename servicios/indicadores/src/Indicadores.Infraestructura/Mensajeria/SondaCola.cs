using System.Collections.Concurrent;
using System.Diagnostics;
using System.Text;
using Prometheus;
using RabbitMQ.Client.Events;

namespace ServiRed.Indicadores.Infraestructura.Mensajeria;

/// <summary>
/// Edad del mensaje más viejo de la cola (ADR-21). Las colas quorum de RabbitMQ no la
/// informan, así que Persistencia publica cada 2 s una sonda (routing key «sonda.v1») con
/// su hora de publicación. La cola es FIFO: todo lo publicado antes de la última sonda
/// consumida ya salió, así que el mensaje más viejo pendiente tiene a lo sumo
/// <c>ahora − publicación de esa sonda</c> (con un margen de 2 s).
/// <para>
/// Antes de recibir la primera sonda se toma la hora de arranque del proceso: un consumidor
/// que no consume nada ve crecer la edad.
/// </para>
/// </summary>
public static class SondaCola
{
    public const string RoutingKey = "sonda.v1";
    public const string Cabecera = "x-sonda-publicada-ms";

    private static readonly Gauge MasViejo = Metrics.CreateGauge("servired_cola_mensaje_mas_viejo_segundos",
        "Cota superior de la edad del mensaje más viejo de la cola (sonda; ADR-21)", "cola");

    private static readonly Histogram Espera = Metrics.CreateHistogram("servired_cola_espera_sonda_segundos",
        "Tiempo que cada sonda esperó en la cola antes de consumirse", new HistogramConfiguration
        {
            LabelNames = ["cola"],
            Buckets = [0.1, 0.5, 1, 2, 5, 10, 30, 60, 300],
        });

    private static readonly ConcurrentDictionary<string, long> UltimaPublicadaMs = new();
    /// <summary>Colas que ya recibieron su primera sonda: hasta entonces la referencia es el arranque.</summary>
    private static readonly ConcurrentDictionary<string, bool> ConSonda = new();
    private static readonly long ArranqueMs =
        new DateTimeOffset(Process.GetCurrentProcess().StartTime.ToUniversalTime()).ToUnixTimeMilliseconds();

    static SondaCola()
    {
        // La edad se calcula al momento de cada raspado: si la cola se atasca, sigue creciendo.
        Metrics.DefaultRegistry.AddBeforeCollectCallback(Actualizar);
    }

    /// <summary>Empieza a informar la edad de la cola (desde el arranque del proceso).</summary>
    public static void Registrar(string cola)
    {
        UltimaPublicadaMs.TryAdd(cola, ArranqueMs);
        Actualizar();
    }

    public static bool EsSonda(BasicDeliverEventArgs entrega) => entrega.RoutingKey == RoutingKey;

    /// <summary>Registra una sonda consumida. Devuelve los segundos que esperó (null si no trae la hora).</summary>
    public static double? Recibir(string cola, BasicDeliverEventArgs entrega, TimeProvider? reloj = null)
    {
        var publicadaMs = Leer(entrega.BasicProperties.Headers);
        if (publicadaMs is null) return null;
        // La primera sonda reemplaza la hora de arranque aunque sea más vieja (cola atrasada);
        // después manda la más reciente (otra réplica puede entregar una más vieja).
        UltimaPublicadaMs.AddOrUpdate(cola, publicadaMs.Value,
            (c, previa) => ConSonda.TryAdd(c, true) ? publicadaMs.Value : Math.Max(previa, publicadaMs.Value));
        ConSonda.TryAdd(cola, true);
        var espera = Math.Max(0, ((reloj ?? TimeProvider.System).GetUtcNow().ToUnixTimeMilliseconds() - publicadaMs.Value) / 1000.0);
        Espera.WithLabels(cola).Observe(espera);
        Actualizar();
        return espera;
    }

    private static void Actualizar()
    {
        var ahora = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        foreach (var (cola, ms) in UltimaPublicadaMs)
            MasViejo.WithLabels(cola).Set(Math.Max(0, (ahora - ms) / 1000.0));
    }

    private static long? Leer(IDictionary<string, object?>? cabeceras)
    {
        if (cabeceras is null || !cabeceras.TryGetValue(Cabecera, out var valor)) return null;
        return valor switch
        {
            long l => l,
            int i => i,
            byte[] b when long.TryParse(Encoding.UTF8.GetString(b), out var l) => l,
            _ => null,
        };
    }
}
