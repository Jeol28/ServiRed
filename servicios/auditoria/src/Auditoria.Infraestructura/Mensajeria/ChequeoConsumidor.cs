using Microsoft.Extensions.Diagnostics.HealthChecks;

namespace ServiRed.Auditoria.Infraestructura.Mensajeria;

/// <summary>Readiness: el servicio solo está listo si está consumiendo su cola.</summary>
public sealed class ChequeoConsumidor(EstadoConsumidor estado, OpcionesConsumidor opciones) : IHealthCheck
{
    public Task<HealthCheckResult> CheckHealthAsync(HealthCheckContext context, CancellationToken ct = default) =>
        Task.FromResult(estado.Activo
            ? HealthCheckResult.Healthy($"Consumiendo {opciones.Cola}")
            : HealthCheckResult.Unhealthy($"Sin consumir {opciones.Cola}"));
}
