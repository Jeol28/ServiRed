namespace ServiRed.Auditoria.Dominio;

public enum Rol { CIUDADANO, OPERADOR, REGULADOR, ADMINISTRADOR }

/// <summary>Categorías del registro de auditoría (RF-12).</summary>
public static class Categorias
{
    public const string Acceso = "ACCESO";
    public const string Administracion = "ADMINISTRACION";
    public const string Exportacion = "EXPORTACION";
    public const string Moderacion = "MODERACION";
    public const string Seguridad = "SEGURIDAD";
    public const string CambioEstado = "CAMBIO_ESTADO";
    public const string Validacion = "VALIDACION";
    public const string Reporte = "REPORTE";
    public const string Alerta = "ALERTA";

    public static readonly IReadOnlySet<string> Todas = new HashSet<string>
        { Acceso, Administracion, Exportacion, Moderacion, Seguridad, CambioEstado, Validacion, Reporte, Alerta };
}

public sealed class DatosInvalidos(string campo, string mensaje) : Exception(mensaje)
{
    public string Campo { get; } = campo;
}

public sealed class AccesoDenegado(string mensaje) : Exception(mensaje);

/// <summary>Personal que consulta la auditoría (JWT validado).</summary>
public sealed record Actor(Guid IdCuenta, Rol Rol, Guid? IdEmpresa);

/// <summary>Una entrada del registro de auditoría, ya saneada.</summary>
public sealed record RegistroAuditoria(
    Guid IdEvento,
    string Tipo,
    int Version,
    string Origen,
    Guid? CorrelationId,
    string Categoria,
    string Accion,
    string? Resultado,
    Guid? ActorId,
    string? ActorRol,
    string? RecursoTipo,
    string? RecursoId,
    IReadOnlyDictionary<string, object?> Detalle,
    DateTimeOffset OcurridoEn);
