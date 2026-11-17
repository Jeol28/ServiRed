using ServiRed.Indicadores.Dominio;

namespace ServiRed.Indicadores.Aplicacion;

public enum Agrupacion { NINGUNA, SERVICIO, DEPARTAMENTO, MUNICIPIO, MUNICIPIO_SERVICIO_MES, CELDA }

public sealed record FiltroVista(
    Periodo Periodo,
    TipoServicio? TipoServicio = null,
    string? CodigoDepartamento = null,
    string? CodigoMunicipio = null,
    Guid? IdEmpresa = null,
    Situacion? Situacion = null);

/// <summary>Lo que un evento aporta a la fila del reporte; null = no lo trae.</summary>
public sealed record ActualizacionVista(
    Guid IdReporte,
    DateTimeOffset FechaEvento,
    string? TipoServicio = null,
    string? CodigoMunicipio = null,
    double? Latitud = null,
    double? Longitud = null,
    string? Magnitud = null,
    DateTimeOffset? InicioEstimado = null,
    DateTimeOffset? FechaRegistro = null,
    DateTimeOffset? FechaRestablecimiento = null,
    int? DuracionMinutos = null,
    bool? Validado = null,
    int? ConfirmacionesNetas = null,
    string? EstadoCaso = null,
    string? Prioridad = null,
    Guid? IdEmpresa = null,
    DateTimeOffset? ResueltoEn = null,
    DateTimeOffset? CerradoEn = null);

/// <summary>Fila de la vista (sin ciudadano ni descripción), para el mapa operativo y la exportación.</summary>
public sealed record Hecho(
    Guid IdReporte,
    string TipoServicio,
    string CodigoMunicipio,
    string NombreMunicipio,
    string Departamento,
    double Latitud,
    double Longitud,
    string? Magnitud,
    DateTimeOffset? InicioEstimado,
    DateTimeOffset FechaRegistro,
    DateTimeOffset? FechaRestablecimiento,
    int? DuracionMinutos,
    bool Validado,
    int ConfirmacionesNetas,
    string? EstadoCaso,
    string? Prioridad,
    Guid? IdEmpresa,
    DateTimeOffset? ResueltoEn,
    DateTimeOffset? CerradoEn);

public sealed record PaginaHechos(IReadOnlyList<Hecho> Elementos, bool HayMas);

public sealed record EstadoVista(long Reportes, long Municipios, DateTimeOffset? PrimerReporte,
    DateTimeOffset? UltimoReporte, DateTimeOffset? ActualizadoEn);

/// <summary>Vista de lectura en Persistencia (actualizarVistaReporte, calcularAgregados...).</summary>
public interface IVistaLectura
{
    Task ActualizarAsync(ActualizacionVista a, IdCorrelacion correlacion, CancellationToken ct);

    Task<IReadOnlyList<Agregado>> AgregarAsync(FiltroVista filtro, Agrupacion agrupacion, double celdaGrados,
        IdCorrelacion correlacion, CancellationToken ct);

    Task<PaginaHechos> HechosAsync(FiltroVista filtro, int desde, int limite, IdCorrelacion correlacion, CancellationToken ct);

    Task<EstadoVista> EstadoAsync(IdCorrelacion correlacion, CancellationToken ct);
}

/// <summary>El mensaje nunca podrá procesarse (va directo a la DLQ).</summary>
public sealed class FallaPermanente(string mensaje, Exception? causa = null) : Exception(mensaje, causa);

/// <summary>Acción auditable (evento accion.auditada v1, RF-12); sin datos personales.</summary>
public sealed record AccionAuditada(string Categoria, string Accion, Actor Actor, string RecursoTipo,
    IReadOnlyDictionary<string, object?> Detalle);

/// <summary>Publica una acción auditable a través del outbox de Persistencia.</summary>
public interface IRegistroAcciones
{
    Task PublicarAsync(AccionAuditada accion, IdCorrelacion correlacion, CancellationToken ct);
}
