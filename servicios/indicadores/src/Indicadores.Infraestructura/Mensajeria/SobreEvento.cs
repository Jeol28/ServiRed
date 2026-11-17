using System.Text.Json;

namespace ServiRed.Indicadores.Infraestructura.Mensajeria;

/// <summary>Sobre común de eventos (contratos/eventos/esquemas/sobre-evento.v1.json).</summary>
public sealed record SobreEvento(
    Guid IdEvento,
    string Tipo,
    int Version,
    DateTimeOffset FechaOcurrencia,
    Guid CorrelationId,
    string Origen,
    JsonElement Datos);
