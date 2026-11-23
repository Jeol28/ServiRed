namespace ServiRed.Atencion.Dominio;

/// <summary>
/// Zona de servicio y su única empresa dueña para un servicio (RF-6, ADR-35). Solo esa empresa
/// gestiona los casos que caen allí. <see cref="IdEmpresa"/> es null si nadie presta ese
/// servicio en la zona: el caso queda sin empresa.
/// </summary>
public sealed record Cobertura(string IdZona, string NombreZona, Guid? IdEmpresa, string? NombreEmpresa);

/// <summary>Empresa prestadora real del catálogo (ADR-35), con los servicios que presta.</summary>
/// Un municipio o departamento en prestación directa puede no tener NIT conocido (ADR-37).
public sealed record EmpresaPrestadora(Guid IdEmpresa, string? Nit, string Nombre, IReadOnlyList<string> Servicios);

/// <summary>
/// Filtro del catálogo de empresas (null = sin filtro): las dueñas de al menos una zona con ese
/// servicio, en ese departamento o municipio.
/// </summary>
public sealed record FiltroEmpresas(TipoServicio? TipoServicio = null, string? CodigoDepartamento = null,
    string? CodigoMunicipio = null);

/// <summary>
/// Dónde buscar la cobertura: el punto del reporte si lo hay; si no, la ubicación guardada de
/// la denuncia (su evento no la trae); si no, la cabecera del municipio.
/// </summary>
public sealed record ConsultaCobertura(TipoServicio Servicio, double? Latitud = null, double? Longitud = null,
    Guid? IdDenuncia = null, string? CodigoMunicipio = null)
{
    public static ConsultaCobertura DeCaso(Caso c) => new(c.TipoServicio, c.Latitud, c.Longitud, null, c.CodigoMunicipio);
}
