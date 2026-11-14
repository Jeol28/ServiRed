using System.Diagnostics;
using ServiRed.Auditoria.Dominio;

namespace ServiRed.Auditoria.Api.Correlacion;

/// <summary>
/// Abre un scope de log con el correlationId (aparece en todos los logs JSON de la
/// petición), lo devuelve en la respuesta y registra método, ruta, estado y duración.
/// Nunca registra IP, cabeceras ni cuerpo (RNF-OBS2, RNF-SEG4).
/// </summary>
public sealed class MiddlewareCorrelacion(RequestDelegate siguiente, ILogger<MiddlewareCorrelacion> log)
{
    public const string ClaveItem = "correlationId";

    public async Task InvokeAsync(HttpContext contexto)
    {
        var id = IdCorrelacion.DesdeCabecera(contexto.Request.Headers[IdCorrelacion.Cabecera]);
        contexto.Items[ClaveItem] = id;
        contexto.Response.Headers[IdCorrelacion.Cabecera] = id.Valor;

        using var _ = log.BeginScope(new Dictionary<string, object> { ["correlationId"] = id.Valor });
        var reloj = Stopwatch.StartNew();
        try
        {
            await siguiente(contexto);
        }
        finally
        {
            var ruta = contexto.Request.Path.Value ?? "";
            var nivel = ruta.StartsWith("/health") || ruta.StartsWith("/metrics") ? LogLevel.Debug : LogLevel.Information;
            log.Log(nivel, "peticion atendida {Metodo} {Ruta} {Estado} {DuracionMs}",
                contexto.Request.Method, ruta, contexto.Response.StatusCode, reloj.ElapsedMilliseconds);
        }
    }
}
