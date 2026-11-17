using ServiRed.Indicadores.Api.Seguridad;
using ServiRed.Indicadores.Dominio;

namespace ServiRed.Indicadores.Api.Endpoints;

/// <summary>Traduce las excepciones a problem+json, sin detalles internos.</summary>
public sealed class ManejoErrores(RequestDelegate siguiente, ILogger<ManejoErrores> log)
{
    public async Task InvokeAsync(HttpContext contexto)
    {
        try
        {
            await siguiente(contexto);
        }
        catch (Exception e) when (!contexto.Response.HasStarted)
        {
            var (estado, titulo, detalle, extras) = e switch
            {
                DatosInvalidos d => (400, "Datos inválidos", d.Message,
                    new Dictionary<string, object?> { ["errores"] = new[] { new { campo = d.Campo, mensaje = d.Message } } }),
                BadHttpRequestException => (400, "Solicitud inválida", "Los parámetros no son válidos", null),
                AccesoDenegado a => (403, "Prohibido", a.Message, null),
                HttpRequestException or TaskCanceledException => (503, "Servicio no disponible",
                    "No fue posible calcular los indicadores; intente de nuevo", null),
                _ => (500, "Error interno", "Ocurrió un error inesperado; use el correlationId para reportarlo", null),
            };
            if (estado >= 500) log.LogError(e, "error al atender la petición");
            await Problemas.EscribirAsync(contexto, estado, titulo, detalle, extras);
        }
    }
}
