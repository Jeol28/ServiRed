using Microsoft.Extensions.Configuration;

namespace ServiRed.Auditoria.Infraestructura.Persistencia;

/// <summary>
/// Conexión con el servicio de Persistencia. La clave es la de ESTE servicio
/// (PERSISTENCIA_CLAVE_AUDITORIA en .env); Auditoría nunca tiene credenciales de BD.
/// </summary>
public sealed record OpcionesPersistencia(Uri Url, string Clave, TimeSpan Timeout)
{
    public static OpcionesPersistencia DesdeConfiguracion(IConfiguration c) => new(
        new Uri(c["PERSISTENCIA_URL"] ?? "http://persistencia:8080"),
        c["PERSISTENCIA_CLAVE"] ?? "",
        TimeSpan.FromSeconds(int.TryParse(c["PERSISTENCIA_TIMEOUT_S"], out var s) ? s : 5));
}
