using Microsoft.Extensions.Configuration;

namespace ServiRed.Indicadores.Infraestructura.Mensajeria;

/// <summary>
/// Cola propia de Indicadores (infra/rabbitmq/definiciones.json). Tras
/// <see cref="MaxIntentos"/> fallas transitorias el mensaje va a la DLQ.
/// </summary>
public sealed record OpcionesConsumidor(string Cola, ushort Prefetch, int MaxIntentos)
{
    public string ColaReintento => Cola + ".reintento";

    public static OpcionesConsumidor DesdeConfiguracion(IConfiguration c) => new(
        c["CONSUMIDOR_COLA"] ?? "indicadores.eventos",
        ushort.TryParse(c["CONSUMIDOR_PREFETCH"], out var p) ? p : (ushort)10,
        int.TryParse(c["CONSUMIDOR_MAX_INTENTOS"], out var m) ? m : 5);
}
