using Microsoft.AspNetCore.Diagnostics.HealthChecks;
using Prometheus;
using ServiRed.Atencion.Api.Correlacion;
using ServiRed.Atencion.Infraestructura;

// SERVI-RED · Atención operacional: casos, enrutamiento, priorización y moderación (RF-6, RF-7)
const string NombreServicio = "atencion";

var builder = WebApplication.CreateBuilder(args);

// Logs JSON estructurados (RNF-OBS2): incluyen el scope con correlationId.
builder.Logging.ClearProviders();
builder.Logging.AddJsonConsole(o =>
{
    o.IncludeScopes = true;
    o.UseUtcTimestamp = true;
    o.TimestampFormat = "yyyy-MM-ddTHH:mm:ss.fffZ";
});

builder.Services.AgregarInfraestructura(builder.Configuration, NombreServicio);

var app = builder.Build();

app.UseMiddleware<MiddlewareCorrelacion>();
app.UseHttpMetrics();

// Liveness: el proceso responde. Readiness: sus dependencias están disponibles.
app.MapHealthChecks("/health/live", new HealthCheckOptions { Predicate = _ => false });
app.MapHealthChecks("/health/ready", new HealthCheckOptions
{
    Predicate = c => c.Tags.Contains(ServiciosInfraestructura.EtiquetaReadiness),
});
app.MapMetrics("/metrics");

app.Run();

/// <summary>Expuesto para las pruebas de integración (WebApplicationFactory).</summary>
public partial class Program;
