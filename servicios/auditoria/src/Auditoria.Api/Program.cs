using Microsoft.AspNetCore.Diagnostics.HealthChecks;
using System.Text.Json.Serialization;
using Prometheus;
using ServiRed.Auditoria.Api.Endpoints;
using ServiRed.Auditoria.Api.Seguridad;
using ServiRed.Auditoria.Api.Correlacion;
using ServiRed.Auditoria.Infraestructura;

// SERVI-RED · Auditoría: consumidor de eventos, registro de auditoría y alertas (RF-12, RF-13)
const string NombreServicio = "auditoria";

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
builder.Services.AgregarSeguridad(builder.Configuration);
builder.Services.ConfigureHttpJsonOptions(o =>
{
    o.SerializerOptions.Converters.Add(new JsonStringEnumConverter());
    o.SerializerOptions.DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull;
});

var app = builder.Build();

app.UseMiddleware<MiddlewareCorrelacion>();
app.UseMiddleware<ManejoErrores>();
app.UseHttpMetrics();
app.UseAuthentication();
app.UseAuthorization();

// Liveness: el proceso responde. Readiness: sus dependencias están disponibles.
app.MapHealthChecks("/health/live", new HealthCheckOptions { Predicate = _ => false });
app.MapHealthChecks("/health/ready", new HealthCheckOptions
{
    Predicate = c => c.Tags.Contains(ServiciosInfraestructura.EtiquetaReadiness),
});
app.MapMetrics("/metrics");
app.MapearAuditoria();

app.Run();

/// <summary>Expuesto para las pruebas de integración (WebApplicationFactory).</summary>
public partial class Program;
