using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.IdentityModel.JsonWebTokens;
using Microsoft.IdentityModel.Protocols.OpenIdConnect;
using Microsoft.IdentityModel.Tokens;
using ServiRed.Auditoria.Aplicacion;
using ServiRed.Auditoria.Dominio;

namespace ServiRed.Auditoria.Pruebas;

public class ApiPruebas : IClassFixture<ApiPruebas.Fabrica>
{
    public const string TokenAlertas = "token-alertas-de-prueba-123";

    public sealed class Fabrica : WebApplicationFactory<Program>
    {
        public static readonly RsaSecurityKey Clave = new(RSA.Create(2048)) { KeyId = "clave-prueba" };
        public readonly RepositorioFalso Repo = new();

        protected override void ConfigureWebHost(Microsoft.AspNetCore.Hosting.IWebHostBuilder b) =>
            b.UseSetting("ALERTAS_TOKEN", TokenAlertas).ConfigureTestServices(s =>
            {
                s.AddSingleton<IRepositorioAuditoria>(Repo);
                foreach (var d in s.Where(d => d.ImplementationType?.Name == "ConsumidorEventos").ToList()) s.Remove(d);
                s.PostConfigure<JwtBearerOptions>(JwtBearerDefaults.AuthenticationScheme, o =>
                {
                    var c = new OpenIdConnectConfiguration();
                    c.SigningKeys.Add(Clave);
                    o.ConfigurationManager = new Microsoft.IdentityModel.Protocols.StaticConfigurationManager<OpenIdConnectConfiguration>(c);
                });
            });
    }

    private readonly Fabrica _fabrica;
    private readonly HttpClient _cliente;

    public ApiPruebas(Fabrica fabrica)
    {
        _fabrica = fabrica;
        _cliente = fabrica.CreateClient();
    }

    private static string Token(Rol rol) => new JsonWebTokenHandler().CreateToken(new SecurityTokenDescriptor
    {
        Issuer = "servired-identidad", Audience = "servired-api",
        Claims = new Dictionary<string, object> { ["sub"] = Guid.NewGuid().ToString(), ["rol"] = rol.ToString() },
        Expires = DateTime.UtcNow.AddMinutes(10),
        SigningCredentials = new SigningCredentials(Fabrica.Clave, SecurityAlgorithms.RsaSha256),
    });

    private Task<HttpResponseMessage> Get(string ruta, string? token)
    {
        var p = new HttpRequestMessage(HttpMethod.Get, ruta);
        if (token is not null) p.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        return _cliente.SendAsync(p);
    }

    [Fact]
    public async Task SeFiltraPorRolDelActorYTipoDeRecursoSinNecesitarIds()
    {
        var r = await Get("/api/v1/auditoria/registros?actorRol=administrador&recursoTipo=caso", Token(Rol.REGULADOR));

        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        Assert.Equal("ADMINISTRADOR", _fabrica.Repo.UltimoFiltro!.ActorRol);
        Assert.Equal("CASO", _fabrica.Repo.UltimoFiltro.RecursoTipo);
        Assert.Null(_fabrica.Repo.UltimoFiltro.ActorId);
    }

    [Fact]
    public async Task LaAuditoriaSoloLaConsultanReguladorYAdministrador()
    {
        Assert.Equal(HttpStatusCode.Unauthorized, (await Get("/api/v1/auditoria/registros", null)).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await Get("/api/v1/auditoria/registros", Token(Rol.OPERADOR))).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await Get("/api/v1/auditoria/registros", Token(Rol.REGULADOR))).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await Get("/api/v1/auditoria/integridad", Token(Rol.REGULADOR))).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await Get("/api/v1/auditoria/integridad", Token(Rol.ADMINISTRADOR))).StatusCode);
    }

    [Fact]
    public async Task LasAnclasSonPublicasSinToken()
    {
        var r = await Get("/api/v1/auditoria/anclas", null);
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        // El resto de la auditoría sigue exigiendo rol.
        Assert.Equal(HttpStatusCode.Unauthorized, (await Get("/api/v1/auditoria/registros", null)).StatusCode);
    }

    [Fact]
    public async Task ElWebhookDeAlertmanagerExigeElToken()
    {
        const string cuerpo = """
            {"version":"4","status":"firing","alerts":[{"status":"firing","labels":{"alertname":"ServicioCaido",
             "severity":"critica","servicio":"reportes"},"annotations":{"summary":"reportes no responde"},
             "startsAt":"2026-11-17T12:00:00Z","fingerprint":"f1"}]}
            """;
        HttpRequestMessage Peticion(string? token)
        {
            var p = new HttpRequestMessage(HttpMethod.Post, "/interno/v1/alertas")
                { Content = new StringContent(cuerpo, Encoding.UTF8, "application/json") };
            if (token is not null) p.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
            return p;
        }

        Assert.Equal(HttpStatusCode.Unauthorized, (await _cliente.SendAsync(Peticion(null))).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await _cliente.SendAsync(Peticion("otro-token-cualquiera-1234"))).StatusCode);
        var r = await _cliente.SendAsync(Peticion(TokenAlertas));
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        Assert.Contains(_fabrica.Repo.Registros.Values, x => x.Accion == "ServicioCaido" && x.Categoria == Categorias.Alerta);

        var alertas = await (await Get("/api/v1/auditoria/alertas", Token(Rol.ADMINISTRADOR))).Content
            .ReadFromJsonAsync<System.Text.Json.JsonElement>();
        Assert.True(alertas.GetProperty("elementos").GetArrayLength() >= 1);
    }
}
