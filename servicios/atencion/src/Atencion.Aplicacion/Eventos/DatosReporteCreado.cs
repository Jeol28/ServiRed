namespace ServiRed.Atencion.Aplicacion.Eventos;

/// <summary>
/// Datos de reporte.creado v1 que Atención necesita
/// (contratos/eventos/esquemas/reporte.creado.v1.json).
/// </summary>
public sealed record DatosReporteCreado(
    Guid IdReporte,
    string TipoServicio,
    string CodigoMunicipio,
    string Magnitud,
    DateTimeOffset FechaRegistro);
