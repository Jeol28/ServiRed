using System.Text.Json;
using ServiRed.Auditoria.Dominio;

namespace ServiRed.Auditoria.Aplicacion;

public sealed record FiltroAuditoria(string? Categoria = null, string? Accion = null, Guid? ActorId = null,
    string? RecursoId = null, DateTimeOffset? Desde = null, DateTimeOffset? Hasta = null, int Pagina = 0, int Tamano = 50);

public sealed record RegistroConsultado(long Secuencia, Guid IdEvento, string Tipo, int Version, string Origen,
    Guid? CorrelationId, string Categoria, string Accion, string? Resultado, Guid? ActorId, string? ActorRol,
    string? RecursoTipo, string? RecursoId, JsonElement? Detalle, DateTimeOffset OcurridoEn,
    DateTimeOffset RegistradoEn, string Hash);

public sealed record PaginaAuditoria(IReadOnlyList<RegistroConsultado> Elementos, long Total, int Pagina, int Tamano);

/// <summary>Integridad de toda la cadena: la parte en la base y la archivada en segmentos (ADR-29).</summary>
public sealed record Verificacion(long Registros, bool Integra, long? PrimeraRota, string? UltimoHash,
    long? RegistrosEnBase = null, long? RegistrosArchivados = null, int? Segmentos = null, long? SegmentoRoto = null);

/// <summary>
/// Ancla diaria de la cadena (ADR-31): el último hash de ese día, publicado también fuera del
/// sistema. Pública: solo hashes y conteos, sin datos de personas.
/// </summary>
public sealed record AnclaPublica(DateOnly Fecha, long Secuencia, string Hash, long Registros, int Segmentos,
    DateTimeOffset? PublicadaEn, string? Destino, string? Referencia);

/// <summary>Registro de auditoría en Persistencia (registrarAuditoria, consultarAuditoria, verificarIntegridad).</summary>
public interface IRepositorioAuditoria
{
    /// <returns>false si el evento ya estaba registrado (idempotente)</returns>
    Task<bool> RegistrarAsync(RegistroAuditoria registro, IdCorrelacion correlacion, CancellationToken ct);

    Task<PaginaAuditoria> ConsultarAsync(FiltroAuditoria filtro, IdCorrelacion correlacion, CancellationToken ct);

    Task<Verificacion> VerificarAsync(IdCorrelacion correlacion, CancellationToken ct);

    Task<IReadOnlyList<AnclaPublica>> AnclasAsync(IdCorrelacion correlacion, CancellationToken ct);
}

/// <summary>Expone las alertas como métrica para Prometheus/Alertmanager.</summary>
public interface IMetricasAlertas
{
    void Registrar(Alerta alerta);
}

/// <summary>El mensaje nunca podrá procesarse (va directo a la DLQ).</summary>
public sealed class FallaPermanente(string mensaje, Exception? causa = null) : Exception(mensaje, causa);

/// <summary>Alerta recibida de Alertmanager (webhook).</summary>
public sealed record AlertaExterna(string Nombre, string Estado, string? Severidad, string? Servicio, string? Resumen,
    DateTimeOffset Inicio, string Huella);
