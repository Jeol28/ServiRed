using Microsoft.Extensions.Diagnostics.HealthChecks;

namespace ServiRed.Atencion.Infraestructura.RabbitMq;

/// <summary>Readiness: el servicio solo está listo si puede hablar con el broker.</summary>
public sealed class ChequeoRabbitMq(ConexionRabbitMq conexion) : IHealthCheck
{
    public async Task<HealthCheckResult> CheckHealthAsync(HealthCheckContext context, CancellationToken ct = default)
    {
        try
        {
            var c = await conexion.ObtenerAsync(ct);
            return c.IsOpen
                ? HealthCheckResult.Healthy("Conectado a RabbitMQ")
                : HealthCheckResult.Unhealthy("Conexión a RabbitMQ cerrada");
        }
        catch (Exception e)
        {
            return HealthCheckResult.Unhealthy("RabbitMQ no disponible", e);
        }
    }
}
