namespace ServiRed.Indicadores.Dominio;

/// <summary>
/// Periodo de un indicador (RNF-DAT4): [Desde, Hasta). Por defecto los últimos 30
/// días; máximo 366 días para que una consulta no recorra toda la historia.
/// </summary>
public sealed record Periodo(DateTimeOffset Desde, DateTimeOffset Hasta)
{
    public static readonly TimeSpan PorDefecto = TimeSpan.FromDays(30);
    public static readonly TimeSpan Maximo = TimeSpan.FromDays(366);

    public static Periodo Crear(DateTimeOffset? desde, DateTimeOffset? hasta, DateTimeOffset ahora)
    {
        var fin = hasta ?? ahora;
        var inicio = desde ?? fin - PorDefecto;
        if (inicio >= fin) throw new DatosInvalidos("desde", "debe ser anterior a hasta");
        if (fin - inicio > Maximo) throw new DatosInvalidos("hasta", "el periodo no puede superar 366 días");
        return new Periodo(inicio, fin);
    }
}
