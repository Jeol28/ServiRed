using System.Text.Json;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.IdentityModel.Protocols;
using Microsoft.IdentityModel.Protocols.OpenIdConnect;
using Microsoft.IdentityModel.Tokens;
using ServiRed.Atencion.Dominio;

namespace ServiRed.Atencion.Api.Seguridad;

/// <summary>
/// Atención revalida el JWT de Identidad (decisión F): firma RS256 con el JWKS
/// publicado en la red interna, emisor, audiencia y expiración. El JWKS se
/// cachea y se vuelve a pedir si llega un token firmado con una clave nueva.
/// </summary>
public static class ConfiguracionJwt
{
    public const string PoliticaLeerCasos = "casos:leer";
    public const string PoliticaGestionarCasos = "casos:gestionar";

    public static IServiceCollection AgregarSeguridad(this IServiceCollection servicios, IConfiguration configuracion)
    {
        var jwksUrl = configuracion["IDENTIDAD_JWKS_URL"] ?? "http://identidad:8080/.well-known/jwks.json";
        servicios.AddAuthentication(JwtBearerDefaults.AuthenticationScheme)
            .AddJwtBearer(o =>
            {
                o.MapInboundClaims = false;
                o.RequireHttpsMetadata = false;
                o.ConfigurationManager = new ConfigurationManager<OpenIdConnectConfiguration>(
                    jwksUrl, new RecuperadorJwks(), new HttpDocumentRetriever { RequireHttps = false })
                {
                    AutomaticRefreshInterval = TimeSpan.FromMinutes(10),
                    RefreshInterval = TimeSpan.FromSeconds(30),
                };
                o.TokenValidationParameters = new TokenValidationParameters
                {
                    ValidIssuer = configuracion["JWT_EMISOR"] ?? "servired-identidad",
                    ValidAudience = configuracion["JWT_AUDIENCIA"] ?? "servired-api",
                    ValidAlgorithms = [SecurityAlgorithms.RsaSha256],
                    RoleClaimType = "rol",
                    NameClaimType = "sub",
                    ClockSkew = TimeSpan.FromSeconds(30),
                };
                o.Events = new JwtBearerEvents
                {
                    OnChallenge = async contexto =>
                    {
                        contexto.HandleResponse();
                        await Problemas.EscribirAsync(contexto.HttpContext, 401, "No autenticado",
                            "Se requiere un token de acceso válido");
                    },
                    OnForbidden = contexto => Problemas.EscribirAsync(contexto.HttpContext, 403, "Prohibido",
                        "Su rol no tiene permiso para esta operación"),
                };
            });
        servicios.AddAuthorizationBuilder()
            .AddPolicy(PoliticaLeerCasos, p => p.RequireRole(
                nameof(Rol.OPERADOR), nameof(Rol.REGULADOR), nameof(Rol.ADMINISTRADOR)))
            .AddPolicy(PoliticaGestionarCasos, p => p.RequireRole(nameof(Rol.OPERADOR), nameof(Rol.ADMINISTRADOR)));
        return servicios;
    }

    /// <summary>Convierte el JWKS de Identidad en la configuración que espera JwtBearer.</summary>
    private sealed class RecuperadorJwks : IConfigurationRetriever<OpenIdConnectConfiguration>
    {
        public async Task<OpenIdConnectConfiguration> GetConfigurationAsync(string direccion, IDocumentRetriever recuperador,
            CancellationToken cancel)
        {
            var json = await recuperador.GetDocumentAsync(direccion, cancel);
            var configuracion = new OpenIdConnectConfiguration();
            foreach (var clave in new JsonWebKeySet(json).GetSigningKeys())
                configuracion.SigningKeys.Add(clave);
            return configuracion;
        }
    }
}

/// <summary>Usuario del personal según el JWT ya validado.</summary>
public static class ActorActual
{
    public static Actor De(HttpContext contexto)
    {
        var usuario = contexto.User;
        var sub = usuario.FindFirst("sub")?.Value;
        var rol = usuario.FindFirst("rol")?.Value;
        if (!Guid.TryParse(sub, out var id) || !Enum.TryParse<Rol>(rol, out var r))
            throw new AccesoDenegado("Token sin identidad válida");
        Guid? empresa = Guid.TryParse(usuario.FindFirst("emp")?.Value, out var e) ? e : null;
        return new Actor(id, r, empresa);
    }
}

/// <summary>Respuestas application/problem+json (RFC 9457) con el correlationId.</summary>
public static class Problemas
{
    public static async Task EscribirAsync(HttpContext contexto, int estado, string titulo, string detalle,
        IDictionary<string, object?>? extras = null)
    {
        contexto.Response.StatusCode = estado;
        contexto.Response.ContentType = "application/problem+json";
        var cuerpo = new Dictionary<string, object?>
        {
            ["title"] = titulo,
            ["status"] = estado,
            ["detail"] = detalle,
            ["correlationId"] = contexto.Items[Correlacion.MiddlewareCorrelacion.ClaveItem]?.ToString(),
        };
        if (extras is not null)
            foreach (var (k, v) in extras) cuerpo[k] = v;
        await contexto.Response.WriteAsync(JsonSerializer.Serialize(cuerpo));
    }
}
