namespace ServiRed.Indicadores.Dominio;

/// <summary>Conteos y estadísticas de un grupo de reportes, tal como los calcula la vista de lectura.</summary>
public sealed record Agregado(
    string? Codigo,
    string? Nombre,
    string? Departamento,
    string? TipoServicio,
    DateTimeOffset? Mes,
    double? CeldaLatitud,
    double? CeldaLongitud,
    long Reportes,
    long ConDuracion,
    double? DuracionPromedio,
    double? DuracionMediana,
    double? DuracionP90,
    long Vivienda,
    long Cuadra,
    long Barrio,
    long Sector,
    long Validados,
    long Resueltos,
    double? TiempoResolucionPromedio,
    // Solo en celdas: si es de una cabecera municipal (true) o rural (false); null = sin clasificar (ADR-28).
    bool? EnCabecera = null);
