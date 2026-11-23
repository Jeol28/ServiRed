using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using ServiRed.Auditoria.Api.Correlacion;
using ServiRed.Auditoria.Api.Seguridad;
using ServiRed.Auditoria.Aplicacion;
using ServiRed.Auditoria.Dominio;

namespace ServiRed.Auditoria.Api.Endpoints;

/// <summary>
/// Consulta del registro de auditoría (RF-12) por el gateway, y webhook interno
/// de Alertmanager (RF-13), que solo es accesible en la red interna y con token.
/// </summary>
public static class EndpointsAuditoria
{
    public static void MapearAuditoria(this WebApplication app)
    {
        var grupo = app.MapGroup("/api/v1/auditoria").RequireAuthorization(ConfiguracionJwt.PoliticaConsultar);

        grupo.MapGet("/registros", (HttpContext h, ConsultasAuditoria c, CancellationToken ct) =>
            c.ConsultarAsync(ActorActual.De(h), Filtro(h, null), Correlacion(h), ct));

        grupo.MapGet("/alertas", (HttpContext h, ConsultasAuditoria c, CancellationToken ct) =>
            c.ConsultarAsync(ActorActual.De(h), Filtro(h, Categorias.Alerta), Correlacion(h), ct));

        grupo.MapGet("/integridad", (HttpContext h, ConsultasAuditoria c, CancellationToken ct) =>
                c.VerificarAsync(ActorActual.De(h), Correlacion(h), ct))
            .RequireAuthorization(ConfiguracionJwt.PoliticaAdministrar);

        // Pública (ADR-31): el último hash de cada día, para que cualquiera lo guarde fuera del sistema.
        app.MapGet("/api/v1/auditoria/anclas", (HttpContext h, ConsultasAuditoria c, CancellationToken ct) =>
            c.AnclasAsync(Correlacion(h), ct));

        app.MapPost("/interno/v1/alertas", async (HttpContext h, RegistroDeAuditoria registro, IConfiguration config,
            CancellationToken ct) =>
        {
            if (!TokenValido(h, config["ALERTAS_TOKEN"])) return Results.Unauthorized();
            using var doc = await JsonDocument.ParseAsync(h.Request.Body, cancellationToken: ct);
            var alertas = Leer(doc.RootElement).ToList();
            var registradas = await registro.RegistrarAlertasExternasAsync(alertas, Correlacion(h), ct);
            return Results.Ok(new { recibidas = alertas.Count, registradas });
        });
    }

    private static FiltroAuditoria Filtro(HttpContext h, string? categoriaFija)
    {
        var q = h.Request.Query;
        DateTimeOffset? Fecha(string campo) =>
            string.IsNullOrWhiteSpace(q[campo]) ? null
            : DateTimeOffset.TryParse(q[campo], CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var f) ? f
            : throw new DatosInvalidos(campo, "fecha ISO 8601 inválida");
        Guid? Id(string campo) =>
            string.IsNullOrWhiteSpace(q[campo]) ? null
            : Guid.TryParse(q[campo], out var g) ? g : throw new DatosInvalidos(campo, "UUID inválido");
        int Entero(string campo, int porDefecto) => int.TryParse(q[campo], out var n) ? n : porDefecto;
        string? Texto(string campo) => string.IsNullOrWhiteSpace(q[campo]) ? null : q[campo].ToString();
        return new FiltroAuditoria(categoriaFija ?? Texto("categoria"), Texto("accion"), Id("actorId"), Texto("recursoId"),
            Fecha("desde"), Fecha("hasta"), Entero("pagina", 0), Entero("tamano", 50),
            Texto("actorRol")?.ToUpperInvariant(), Texto("recursoTipo")?.ToUpperInvariant());
    }

    /// <summary>Token compartido con Alertmanager (ALERTAS_TOKEN); sin token configurado el webhook está cerrado.</summary>
    private static bool TokenValido(HttpContext h, string? esperado)
    {
        if (string.IsNullOrEmpty(esperado) || esperado.Length < 16) return false;
        var recibido = h.Request.Headers.Authorization.ToString();
        var prefijo = "Bearer ";
        if (!recibido.StartsWith(prefijo, StringComparison.Ordinal)) return false;
        return CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(recibido[prefijo.Length..]),
            Encoding.UTF8.GetBytes(esperado));
    }

    /// <summary>Formato del webhook de Alertmanager (version 4).</summary>
    private static IEnumerable<AlertaExterna> Leer(JsonElement raiz)
    {
        if (!raiz.TryGetProperty("alerts", out var alertas) || alertas.ValueKind != JsonValueKind.Array) yield break;
        foreach (var a in alertas.EnumerateArray())
        {
            string? Etiqueta(string grupo, string nombre) =>
                a.TryGetProperty(grupo, out var g) && g.TryGetProperty(nombre, out var v) ? v.GetString() : null;
            var inicio = a.TryGetProperty("startsAt", out var s) && s.TryGetDateTimeOffset(out var f) ? f : DateTimeOffset.UtcNow;
            yield return new AlertaExterna(Etiqueta("labels", "alertname") ?? "DESCONOCIDA",
                a.TryGetProperty("status", out var st) ? st.GetString() ?? "firing" : "firing",
                Etiqueta("labels", "severity"), Etiqueta("labels", "servicio") ?? Etiqueta("labels", "job"),
                Etiqueta("annotations", "summary"),
                inicio, a.TryGetProperty("fingerprint", out var fp) ? fp.GetString() ?? "" : "");
        }
    }

    private static IdCorrelacion Correlacion(HttpContext h) => (IdCorrelacion)h.Items[MiddlewareCorrelacion.ClaveItem]!;
}
