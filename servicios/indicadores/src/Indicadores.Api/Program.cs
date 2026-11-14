using Microsoft.AspNetCore.Diagnostics.HealthChecks;
using Prometheus;
using ServiRed.Indicadores.Api.Correlacion;
using ServiRed.Indicadores.Infraestructura;

// SERVI-RED · Indicadores y datos abiertos: vista de lectura, mapas, anonimización y exportación (RF-8 a RF-10)
const string NombreServicio = "indicadores";

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
