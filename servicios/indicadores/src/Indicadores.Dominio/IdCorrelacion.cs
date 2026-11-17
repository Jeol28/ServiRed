namespace ServiRed.Indicadores.Dominio;

/// <summary>
/// Identificador de correlación de una transacción (RNF-OBS1). Llega desde el
/// gateway o en el sobre de un evento; si falta o no es un UUID válido se genera uno nuevo.
/// </summary>
public sealed record IdCorrelacion
{
    public const string Cabecera = "X-Correlation-Id";

    public string Valor { get; }

    private IdCorrelacion(Guid valor) => Valor = valor.ToString("D");

    public static IdCorrelacion Nuevo() => new(Guid.NewGuid());

    /// <summary>Correlación que viaja en el sobre de un evento (ya es un UUID).</summary>
    public static IdCorrelacion DeEvento(Guid correlationId) =>
        correlationId == Guid.Empty ? throw new ArgumentException("correlationId vacío") : new(correlationId);

    public static IdCorrelacion DesdeCabecera(string? valorRecibido) =>
        valorRecibido is { Length: 36 } && Guid.TryParseExact(valorRecibido, "D", out var guid)
            ? new IdCorrelacion(guid)
            : Nuevo();

    public override string ToString() => Valor;
}
