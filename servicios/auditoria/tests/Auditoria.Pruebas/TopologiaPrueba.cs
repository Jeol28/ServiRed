using RabbitMQ.Client;

namespace ServiRed.Auditoria.Pruebas;

/// <summary>
/// Declara la misma topología que infra/rabbitmq/generar-definiciones.py para
/// la cola de Atención (con un TTL de reintento corto).
/// </summary>
public static class TopologiaPrueba
{
    public const string Cola = "auditoria.eventos";

    public static async Task DeclararAsync(string cadenaConexion, int ttlReintentoMs = 300)
    {
        var fabrica = new ConnectionFactory { Uri = new Uri(cadenaConexion) };
        await using var conexion = await fabrica.CreateConnectionAsync();
        await using var canal = await conexion.CreateChannelAsync();

        await canal.ExchangeDeclareAsync("servired.eventos", ExchangeType.Topic, durable: true);
        await canal.ExchangeDeclareAsync("servired.dlx", ExchangeType.Direct, durable: true);
        await canal.ExchangeDeclareAsync("servired.reintentos", ExchangeType.Direct, durable: true);

        await canal.QueueDeclareAsync(Cola, true, false, false, new Dictionary<string, object?>
        {
            ["x-queue-type"] = "quorum",
            ["x-dead-letter-exchange"] = "servired.dlx",
            ["x-dead-letter-routing-key"] = Cola,
        });
        await canal.QueueDeclareAsync(Cola + ".reintento", true, false, false, new Dictionary<string, object?>
        {
            ["x-queue-type"] = "quorum",
            ["x-message-ttl"] = ttlReintentoMs,
            ["x-dead-letter-exchange"] = "servired.reintentos",
            ["x-dead-letter-routing-key"] = Cola,
        });
        await canal.QueueDeclareAsync(Cola + ".dlq", true, false, false,
            new Dictionary<string, object?> { ["x-queue-type"] = "quorum" });

        foreach (var clave in new[] { "#" })
            await canal.QueueBindAsync(Cola, "servired.eventos", clave);
        await canal.QueueBindAsync(Cola + ".dlq", "servired.dlx", Cola);
        await canal.QueueBindAsync(Cola, "servired.reintentos", Cola);
    }
}
