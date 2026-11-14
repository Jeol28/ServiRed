using System.Text.RegularExpressions;

namespace ServiRed.Atencion.Dominio;

/// <summary>
/// Caso de atención operacional (RF-6). Se abre a partir de un reporte ciudadano
/// y no conoce al ciudadano: solo el reporte, el servicio y el municipio.
/// </summary>
public sealed partial record Caso(
    Guid IdCaso,
    Guid IdReporte,
    TipoServicio TipoServicio,
    string CodigoMunicipio,
    Magnitud Magnitud,
    EstadoCaso Estado,
    DateTimeOffset AbiertoEn)
{
    public static Caso AbrirDesdeReporte(Guid idReporte, TipoServicio tipoServicio, string codigoMunicipio,
        Magnitud magnitud, DateTimeOffset ahora)
    {
        if (idReporte == Guid.Empty)
            throw new ArgumentException("El reporte es obligatorio", nameof(idReporte));
        if (!CodigoDivipola().IsMatch(codigoMunicipio ?? ""))
            throw new ArgumentException("Código DIVIPOLA inválido", nameof(codigoMunicipio));

        return new Caso(Guid.NewGuid(), idReporte, tipoServicio, codigoMunicipio!, magnitud, EstadoCaso.RECIBIDO, ahora);
    }

    [GeneratedRegex("^[0-9]{5}$")]
    private static partial Regex CodigoDivipola();
}
