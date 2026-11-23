using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text.Json;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.IdentityModel.JsonWebTokens;
using Microsoft.IdentityModel.Protocols.OpenIdConnect;
using Microsoft.IdentityModel.Tokens;
using ServiRed.Indicadores.Aplicacion;
using ServiRed.Indicadores.Dominio;

namespace ServiRed.Indicadores.Pruebas;

/// <summary>API pública y del personal con JWT RS256 reales y la vista de lectura en memoria.</summary>
public class ApiPruebas : IClassFixture<ApiPruebas.Fabrica>
{
    public sealed class Fabrica : WebApplicationFactory<Program>
    {
        public static readonly RsaSecurityKey Clave = new(RSA.Create(2048)) { KeyId = "clave-prueba" };
        public readonly VistaFalsa Vista = new();

        protected override void ConfigureWebHost(Microsoft.AspNetCore.Hosting.IWebHostBuilder b) =>
            b.ConfigureTestServices(s =>
            {
                s.AddSingleton<IVistaLectura>(Vista);
                s.AddSingleton<IRegistroAcciones>(Vista);
                foreach (var d in s.Where(d => d.ImplementationType?.Name == "ConsumidorEventos").ToList()) s.Remove(d);
                s.PostConfigure<JwtBearerOptions>(JwtBearerDefaults.AuthenticationScheme, o =>
                {
                    var configuracion = new OpenIdConnectConfiguration();
                    configuracion.SigningKeys.Add(Clave);
                    o.ConfigurationManager =
                        new Microsoft.IdentityModel.Protocols.StaticConfigurationManager<OpenIdConnectConfiguration>(configuracion);
                });
            });
    }

    private readonly Fabrica _fabrica;
    private readonly HttpClient _cliente;
    private static readonly Guid Empresa = Guid.NewGuid();

    public ApiPruebas(Fabrica fabrica)
    {
        _fabrica = fabrica;
        _cliente = fabrica.CreateClient();
        fabrica.Vista.Agregados[Agrupacion.NINGUNA] = [VistaFalsa.Grupo(6, 3)];
        fabrica.Vista.Agregados[Agrupacion.MUNICIPIO_SERVICIO_MES] =
            [VistaFalsa.Grupo(6, 3, servicio: "ENERGIA"), VistaFalsa.Grupo(1, codigo: "05001", servicio: "GAS")];
        fabrica.Vista.Hechos = [VistaFalsa.Hecho(Empresa), VistaFalsa.Hecho(Guid.NewGuid())];
    }

    private static string Token(Rol rol, Guid? empresa = null)
    {
        var claims = new Dictionary<string, object> { ["sub"] = Guid.NewGuid().ToString(), ["rol"] = rol.ToString() };
        if (empresa is not null) claims["emp"] = empresa.ToString()!;
        return new JsonWebTokenHandler().CreateToken(new SecurityTokenDescriptor
        {
            Issuer = "servired-identidad", Audience = "servired-api", Claims = claims,
            Expires = DateTime.UtcNow.AddMinutes(10),
            SigningCredentials = new SigningCredentials(Fabrica.Clave, SecurityAlgorithms.RsaSha256),
        });
    }

    private Task<HttpResponseMessage> Get(string ruta, string? token = null)
    {
        var p = new HttpRequestMessage(HttpMethod.Get, ruta);
        if (token is not null) p.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        return _cliente.SendAsync(p);
    }

    [Fact]
    public async Task ElResumenEsPublicoCacheableYConMetadatos()
    {
        var r = await Get("/api/v1/indicadores/resumen?tipoServicio=ENERGIA");

        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        Assert.Equal("public, max-age=60", r.Headers.CacheControl!.ToString());
        var cuerpo = await r.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal(6, cuerpo.GetProperty("indicadores").GetProperty("reportes").GetInt32());
        Assert.True(cuerpo.GetProperty("metadatos").TryGetProperty("periodo", out _));
        Assert.Equal("ENERGIA", cuerpo.GetProperty("metadatos").GetProperty("filtros").GetProperty("tipoServicio").GetString());
    }

    [Fact]
    public async Task LosDatosAbiertosSeDescarganEnCsvConFilasSuprimidas()
    {
        var r = await Get("/api/v1/datos-abiertos/indicadores-municipales?formato=csv");

        Assert.Equal("text/csv", r.Content.Headers.ContentType!.MediaType);
        Assert.Contains("attachment", r.Content.Headers.ContentDisposition!.ToString());
        var lineas = (await r.Content.ReadAsStringAsync()).Trim().Split('\n');
        Assert.StartsWith("codigo_municipio,municipio", lineas[0]);
        Assert.Contains(lineas, l => l.StartsWith("05001") && l.TrimEnd().EndsWith(",true") && l.Contains(",,"));
        Assert.DoesNotContain("id_reporte", lineas[0]);
    }

    [Fact]
    public async Task LosParametrosInvalidosResponden400ProblemJson()
    {
        var r = await Get("/api/v1/indicadores/resumen?codigoMunicipio=abc");

        Assert.Equal(HttpStatusCode.BadRequest, r.StatusCode);
        Assert.Equal("application/problem+json", r.Content.Headers.ContentType!.MediaType);
        Assert.Equal(HttpStatusCode.BadRequest, (await Get("/api/v1/indicadores/resumen?desde=2020-01-01&hasta=2026-01-01")).StatusCode);
    }

    [Fact]
    public async Task LaExportacionRegulatoriaExigeRegulador()
    {
        Assert.Equal(HttpStatusCode.Unauthorized, (await Get("/api/v1/indicadores/exportaciones/reportes")).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden,
            (await Get("/api/v1/indicadores/exportaciones/reportes", Token(Rol.OPERADOR, Empresa))).StatusCode);

        var r = await Get("/api/v1/indicadores/exportaciones/reportes?formato=csv", Token(Rol.REGULADOR));
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        var csv = await r.Content.ReadAsStringAsync();
        Assert.StartsWith("id_reporte,", csv);
        Assert.Contains("4.629", csv);
        Assert.DoesNotContain("4.628734", csv);
    }

    [Fact]
    public async Task ElMapaOperativoEsDelPersonalYDeSuEmpresa()
    {
        Assert.Equal(HttpStatusCode.Unauthorized, (await Get("/api/v1/mapas/operativo")).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await Get("/api/v1/mapas/operativo", Token(Rol.CIUDADANO))).StatusCode);

        var puntos = await (await Get("/api/v1/mapas/operativo", Token(Rol.OPERADOR, Empresa))).Content
            .ReadFromJsonAsync<JsonElement>();
        Assert.Equal(1, puntos.GetArrayLength());
        Assert.Equal(2, (await (await Get("/api/v1/mapas/operativo", Token(Rol.REGULADOR))).Content
            .ReadFromJsonAsync<JsonElement>()).GetArrayLength());
    }
}
