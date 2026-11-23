using ServiRed.Atencion.Dominio;

namespace ServiRed.Atencion.Aplicacion.Puertos;

/// <summary>
/// Filtros de la bandeja de casos; null = sin filtro. El departamento son los dos
/// primeros dígitos del código DIVIPOLA del municipio.
/// </summary>
public sealed record FiltroCasos(
    EstadoCaso? Estado = null,
    TipoServicio? TipoServicio = null,
    string? CodigoMunicipio = null,
    Prioridad? Prioridad = null,
    Guid? IdEmpresa = null,
    bool SoloSinEmpresa = false,
    Guid? AsignadoA = null,
    Guid? IdReporte = null,
    int Pagina = 0,
    int Tamano = 20,
    string? CodigoDepartamento = null);

/// <summary>Página de resultados; se serializa como {elementos, total, pagina, tamano}, igual que los servicios Java.</summary>
public sealed record Pagina<T>(IReadOnlyList<T> Elementos, long Total,
    [property: System.Text.Json.Serialization.JsonPropertyName("pagina")] int NumeroPagina, int Tamano);

public sealed record Archivo(string TipoContenido, byte[] Contenido);

/// <summary>Otra decisión cambió el caso entre la lectura y la escritura.</summary>
public sealed class ConflictoDeVersion() : Exception("El caso cambió; vuelva a consultarlo");

/// <summary>
/// Operaciones de negocio de Persistencia para el dominio de atención. Las
/// escrituras son idempotentes por X-Correlation-Id y los eventos se guardan en
/// la misma transacción (ADR-06).
/// </summary>
public interface IRepositorioCasos
{
    /// <summary>
    /// Hay a lo sumo un caso por reporte: si ya existía, devuelve el original. Los eventos
    /// (la apertura) se guardan en el outbox solo si el caso se creó.
    /// </summary>
    Task<Caso> AbrirAsync(Caso caso, IReadOnlyList<SobreEventoSaliente> eventos, IdCorrelacion correlacion,
        CancellationToken ct);

    Task<Caso?> ConsultarAsync(Guid idCaso, IdCorrelacion correlacion, CancellationToken ct);

    Task<Caso?> BuscarPorReporteAsync(Guid idReporte, IdCorrelacion correlacion, CancellationToken ct);

    Task<Pagina<Caso>> ListarAsync(FiltroCasos filtro, IdCorrelacion correlacion, CancellationToken ct);

    /// <exception cref="ConflictoDeVersion">si la versión cambió</exception>
    Task<Caso> ActualizarAsync(Guid idCaso, CambiosCaso cambios, IReadOnlyList<SobreEventoSaliente> eventos,
        IdCorrelacion correlacion, CancellationToken ct);

    Task<Archivo?> EvidenciaAsync(Guid idCaso, Guid idEvidencia, IdCorrelacion correlacion, CancellationToken ct);
}

public interface ICatalogoAtencion
{
    /// <summary>La zona de la consulta y su única empresa dueña (ADR-35); null si no se puede ubicar.</summary>
    Task<Cobertura?> CoberturaAsync(ConsultaCobertura consulta, IdCorrelacion correlacion, CancellationToken ct);

    /// <summary>Empresas prestadoras reales, por nombre.</summary>
    Task<IReadOnlyList<EmpresaPrestadora>> EmpresasAsync(IdCorrelacion correlacion, CancellationToken ct);

    /// <summary>¿La cuenta es un operador activo de la empresa? (sin más datos personales).</summary>
    Task<bool> EsOperadorDeAsync(Guid idCuenta, Guid idEmpresa, IdCorrelacion correlacion, CancellationToken ct);
}

public interface IRepositorioModeracion
{
    Task<Moderacion> AbrirAsync(Guid idDenuncia, Guid? idEmpresa, DateTimeOffset recibidaEn, IdCorrelacion correlacion,
        CancellationToken ct);

    Task<Moderacion?> ConsultarAsync(Guid idDenuncia, IdCorrelacion correlacion, CancellationToken ct);

    Task<Pagina<Moderacion>> ListarAsync(EstadoModeracion? estado, Guid? idEmpresa, int pagina, int tamano,
        IdCorrelacion correlacion, CancellationToken ct);

    /// <exception cref="ConflictoDeVersion">si otro moderador decidió primero</exception>
    Task<Moderacion> DecidirAsync(Guid idDenuncia, Decision decision, IReadOnlyList<SobreEventoSaliente> eventos,
        IdCorrelacion correlacion, CancellationToken ct);

    Task<Archivo?> EvidenciaAsync(Guid idDenuncia, Guid idEvidencia, IdCorrelacion correlacion, CancellationToken ct);
}
