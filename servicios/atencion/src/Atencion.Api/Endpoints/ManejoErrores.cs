using ServiRed.Atencion.Aplicacion;
using ServiRed.Atencion.Aplicacion.Puertos;
using ServiRed.Atencion.Api.Seguridad;
using ServiRed.Atencion.Dominio;

namespace ServiRed.Atencion.Api.Endpoints;

/// <summary>Traduce las excepciones del dominio y la aplicación a problem+json, sin detalles internos.</summary>
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
                BadHttpRequestException => (400, "Solicitud inválida", "El cuerpo o los parámetros no son válidos", null),
                AccesoDenegado a => (403, "Prohibido", a.Message, null),
                NoEncontrado n => (404, "No encontrado", n.Message, null),
                ReglaIncumplida r => (409, "Operación no permitida", r.Message,
                    new Dictionary<string, object?> { ["motivo"] = r.Motivo }),
                ConflictoDeVersion => (409, "Conflicto", "El caso cambió mientras se procesaba; intente de nuevo",
                    new Dictionary<string, object?> { ["motivo"] = "VERSION" }),
                HttpRequestException or TaskCanceledException => (503, "Servicio no disponible",
                    "No fue posible completar la operación; intente de nuevo", null),
                _ => (500, "Error interno", "Ocurrió un error inesperado; use el correlationId para reportarlo", null),
            };
            if (estado >= 500) log.LogError(e, "error al atender la petición");
            await Problemas.EscribirAsync(contexto, estado, titulo, detalle, extras);
        }
    }
}
