using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text.Json;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.IdentityModel.JsonWebTokens;
using Microsoft.IdentityModel.Protocols.OpenIdConnect;
using Microsoft.IdentityModel.Tokens;
using ServiRed.Atencion.Aplicacion.Puertos;
using ServiRed.Atencion.Dominio;

namespace ServiRed.Atencion.Pruebas;

/// <summary>
/// API del operador con autenticación real (JWT RS256 firmados en la prueba) y
/// Persistencia en memoria. Sin RabbitMQ: se quita el consumidor.
/// </summary>
public class ApiPruebas : IClassFixture<ApiPruebas.Fabrica>
{
    public sealed class Fabrica : WebApplicationFactory<Program>
    {
        public static readonly RsaSecurityKey Clave = new(RSA.Create(2048)) { KeyId = "clave-prueba" };
        public static readonly RsaSecurityKey OtraClave = new(RSA.Create(2048)) { KeyId = "clave-prueba" };
        public readonly PersistenciaFalsa Persistencia = new();

        protected override void ConfigureWebHost(Microsoft.AspNetCore.Hosting.IWebHostBuilder b) =>
            b.ConfigureTestServices(s =>
            {
                s.AddSingleton<IRepositorioCasos>(Persistencia);
                s.AddSingleton<ICatalogoAtencion>(Persistencia);
                s.AddSingleton<IRepositorioModeracion>(Persistencia);
                foreach (var d in s.Where(d => d.ImplementationType?.Name == "ConsumidorEventosReportes").ToList())
                    s.Remove(d);
                s.PostConfigure<JwtBearerOptions>(JwtBearerDefaults.AuthenticationScheme, o =>
                {
                    var configuracion = new OpenIdConnectConfiguration();
                    configuracion.SigningKeys.Add(Clave);
                    o.ConfigurationManager = new Microsoft.IdentityModel.Protocols
                        .StaticConfigurationManager<OpenIdConnectConfiguration>(configuracion);
                });
            });
    }

    private readonly Fabrica _fabrica;
    private readonly HttpClient _cliente;
    private static readonly Guid OperadorA = Guid.NewGuid();

    public ApiPruebas(Fabrica fabrica)
    {
        _fabrica = fabrica;
        _cliente = fabrica.CreateClient();
    }

    private static string Token(Rol rol, Guid? empresa = null, Guid? sub = null, RsaSecurityKey? clave = null,
        string emisor = "servired-identidad", DateTime? expira = null)
    {
        var claims = new Dictionary<string, object> { ["sub"] = (sub ?? Guid.NewGuid()).ToString(), ["rol"] = rol.ToString() };
        if (empresa is not null) claims["emp"] = empresa.ToString()!;
        return new JsonWebTokenHandler().CreateToken(new SecurityTokenDescriptor
        {
            Issuer = emisor,
            Audience = "servired-api",
            Claims = claims,
            NotBefore = DateTime.UtcNow.AddMinutes(-1),
            Expires = expira ?? DateTime.UtcNow.AddMinutes(10),
            SigningCredentials = new SigningCredentials(clave ?? Fabrica.Clave, SecurityAlgorithms.RsaSha256),
        });
    }

    private async Task<HttpResponseMessage> Enviar(HttpMethod metodo, string ruta, string? token, object? cuerpo = null)
    {
        using var peticion = new HttpRequestMessage(metodo, ruta);
        if (token is not null) peticion.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        if (cuerpo is not null) peticion.Content = JsonContent.Create(cuerpo);
        return await _cliente.SendAsync(peticion);
    }

    private static string Operador() => Token(Rol.OPERADOR, PersistenciaFalsa.EmpresaA, OperadorA);

    [Fact]
    public async Task SinTokenOConTokenInvalidoResponde401ProblemJson()
    {
        var sin = await Enviar(HttpMethod.Get, "/api/v1/casos", null);
        Assert.Equal(HttpStatusCode.Unauthorized, sin.StatusCode);
        Assert.Equal("application/problem+json", sin.Content.Headers.ContentType!.MediaType);

        Assert.Equal(HttpStatusCode.Unauthorized,
            (await Enviar(HttpMethod.Get, "/api/v1/casos", Token(Rol.OPERADOR, clave: Fabrica.OtraClave))).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized,
            (await Enviar(HttpMethod.Get, "/api/v1/casos", Token(Rol.OPERADOR, emisor: "otro"))).StatusCode);
    }

    [Fact]
    public async Task UnCiudadanoNoAccedeALosCasos() =>
        Assert.Equal(HttpStatusCode.Forbidden, (await Enviar(HttpMethod.Get, "/api/v1/casos", Token(Rol.CIUDADANO))).StatusCode);

    [Fact]
    public async Task ElOperadorAtiendeResuelveYCierraUnCasoDeSuEmpresa()
    {
        var caso = _fabrica.Persistencia.Sembrar(PersistenciaFalsa.EmpresaA);

        var atendido = await Enviar(HttpMethod.Post, $"/api/v1/casos/{caso.IdCaso}/atender", Operador());
        Assert.Equal(HttpStatusCode.OK, atendido.StatusCode);
        var cuerpo = await atendido.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("EN_ATENCION", cuerpo.GetProperty("estado").GetString());
        Assert.Equal(OperadorA, cuerpo.GetProperty("asignadoA").GetGuid());

        Assert.Equal(HttpStatusCode.OK, (await Enviar(HttpMethod.Post, $"/api/v1/casos/{caso.IdCaso}/resolver", Operador(),
            new { resolucion = "Se reemplazó el transformador" })).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await Enviar(HttpMethod.Post, $"/api/v1/casos/{caso.IdCaso}/cerrar", Operador(),
            new { resolucion = (string?)null })).StatusCode);
        var final = await (await Enviar(HttpMethod.Get, $"/api/v1/casos/{caso.IdCaso}", Operador())).Content
            .ReadFromJsonAsync<JsonElement>();
        Assert.Equal("CERRADO", final.GetProperty("estado").GetString());
        // Sembrado sin entrada inicial: atender, resolver y cerrar.
        Assert.Equal(3, final.GetProperty("historia").GetArrayLength());
    }

    [Fact]
    public async Task UnaTransicionInvalidaResponde409ConMotivo()
    {
        var caso = _fabrica.Persistencia.Sembrar(PersistenciaFalsa.EmpresaA, EstadoCaso.CERRADO);

        var r = await Enviar(HttpMethod.Post, $"/api/v1/casos/{caso.IdCaso}/atender", Operador());

        Assert.Equal(HttpStatusCode.Conflict, r.StatusCode);
        Assert.Equal("TRANSICION", (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("motivo").GetString());
    }

    [Fact]
    public async Task ElOperadorDeOtraEmpresaNoVeElCaso()
    {
        var caso = _fabrica.Persistencia.Sembrar(PersistenciaFalsa.EmpresaB);

        Assert.Equal(HttpStatusCode.NotFound, (await Enviar(HttpMethod.Get, $"/api/v1/casos/{caso.IdCaso}", Operador())).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound,
            (await Enviar(HttpMethod.Post, $"/api/v1/casos/{caso.IdCaso}/atender", Operador())).StatusCode);
    }

    [Fact]
    public async Task ElReguladorConsultaPeroNoGestiona()
    {
        var caso = _fabrica.Persistencia.Sembrar(PersistenciaFalsa.EmpresaB);
        var regulador = Token(Rol.REGULADOR);

        Assert.Equal(HttpStatusCode.OK, (await Enviar(HttpMethod.Get, $"/api/v1/casos/{caso.IdCaso}", regulador)).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden,
            (await Enviar(HttpMethod.Post, $"/api/v1/casos/{caso.IdCaso}/atender", regulador)).StatusCode);
    }

    [Fact]
    public async Task ElAdministradorSoloEnrutaALaDuenaDeLaZona()
    {
        var caso = _fabrica.Persistencia.Sembrar(PersistenciaFalsa.EmpresaA) with { IdEmpresa = null };
        _fabrica.Persistencia.Casos[caso.IdCaso] = caso;
        var admin = Token(Rol.ADMINISTRADOR);

        var zona = await Enviar(HttpMethod.Get, $"/api/v1/casos/{caso.IdCaso}/zona", admin);
        Assert.Equal(HttpStatusCode.OK, zona.StatusCode);
        var z = await zona.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("11001-U", z.GetProperty("idZona").GetString());
        Assert.Equal(PersistenciaFalsa.EmpresaA, z.GetProperty("idEmpresa").GetGuid());

        // Otra empresa no: solo la dueña de la zona gestiona el caso.
        var fuera = await Enviar(HttpMethod.Put, $"/api/v1/casos/{caso.IdCaso}/empresa", admin,
            new { idEmpresa = PersistenciaFalsa.EmpresaB });
        Assert.Equal(HttpStatusCode.Conflict, fuera.StatusCode);
        Assert.Equal("FUERA_DE_ZONA", (await fuera.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("motivo").GetString());

        var r = await Enviar(HttpMethod.Put, $"/api/v1/casos/{caso.IdCaso}/empresa", admin,
            new { idEmpresa = PersistenciaFalsa.EmpresaA });
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        Assert.Equal(PersistenciaFalsa.EmpresaA, (await r.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("idEmpresa").GetGuid());
        // Un operador no enruta, y el de otra empresa no ve el caso.
        Assert.Equal(HttpStatusCode.Forbidden, (await Enviar(HttpMethod.Put, $"/api/v1/casos/{caso.IdCaso}/empresa",
            Operador(), new { idEmpresa = PersistenciaFalsa.EmpresaA })).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await Enviar(HttpMethod.Get, $"/api/v1/casos/{caso.IdCaso}",
            Token(Rol.OPERADOR, PersistenciaFalsa.EmpresaB))).StatusCode);
    }

    [Fact]
    public async Task LaBandejaSeOrdenaPorPrioridadOPorLoMasReciente()
    {
        var empresa = Guid.NewGuid();
        var vieja = _fabrica.Persistencia.Sembrar(empresa, magnitud: Magnitud.SECTOR) with
            { AbiertoEn = DateTimeOffset.Parse("2026-11-17T10:00:00Z") };
        var nueva = _fabrica.Persistencia.Sembrar(empresa, magnitud: Magnitud.VIVIENDA) with
            { AbiertoEn = DateTimeOffset.Parse("2026-11-17T12:00:00Z") };
        _fabrica.Persistencia.Casos[vieja.IdCaso] = vieja with { Prioridad = Prioridad.CRITICA };
        _fabrica.Persistencia.Casos[nueva.IdCaso] = nueva with { Prioridad = Prioridad.BAJA };
        var operador = Token(Rol.OPERADOR, empresa);

        async Task<Guid[]> Orden(string consulta)
        {
            var r = await Enviar(HttpMethod.Get, "/api/v1/casos" + consulta, operador);
            var json = await r.Content.ReadFromJsonAsync<JsonElement>();
            return json.GetProperty("elementos").EnumerateArray().Select(e => e.GetProperty("idCaso").GetGuid()).ToArray();
        }

        Assert.Equal([vieja.IdCaso, nueva.IdCaso], await Orden(""));
        Assert.Equal([nueva.IdCaso, vieja.IdCaso], await Orden("?orden=RECIENTES"));
        Assert.Equal([vieja.IdCaso, nueva.IdCaso], await Orden("?orden=ANTIGUOS"));
        Assert.Equal(HttpStatusCode.BadRequest,
            (await Enviar(HttpMethod.Get, "/api/v1/casos?orden=ALFABETICO", operador)).StatusCode);
    }

    [Fact]
    public async Task ElPersonalConsultaElCatalogoDeEmpresas()
    {
        var r = await Enviar(HttpMethod.Get, "/api/v1/empresas", Operador());
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        Assert.Equal(2, (await r.Content.ReadFromJsonAsync<JsonElement>()).GetArrayLength());
        // Filtro por servicio y territorio: llega tal cual a Persistencia; un parámetro vacío no filtra.
        var filtrada = await Enviar(HttpMethod.Get,
            "/api/v1/empresas?tipoServicio=ACUEDUCTO&codigoDepartamento=11&codigoMunicipio=", Operador());
        var lista = await filtrada.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal(1, lista.GetArrayLength());
        Assert.Equal("Empresa B", lista[0].GetProperty("nombre").GetString());
        Assert.Equal(new FiltroEmpresas(TipoServicio.ACUEDUCTO, "11", null), _fabrica.Persistencia.UltimoFiltroEmpresas);
        Assert.Equal(HttpStatusCode.Forbidden, (await Enviar(HttpMethod.Get, "/api/v1/empresas",
            Token(Rol.CIUDADANO))).StatusCode);
    }

    [Fact]
    public async Task FijarPrioridadAMano()
    {
        var caso = _fabrica.Persistencia.Sembrar(PersistenciaFalsa.EmpresaA);

        var r = await Enviar(HttpMethod.Put, $"/api/v1/casos/{caso.IdCaso}/prioridad", Operador(), new { prioridad = "CRITICA" });

        var cuerpo = await r.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("CRITICA", cuerpo.GetProperty("prioridad").GetString());
        Assert.True(cuerpo.GetProperty("prioridadManual").GetBoolean());
    }

    [Fact]
    public async Task SeModeraUnaDenunciaDeLaEmpresa()
    {
        var id = Guid.NewGuid();
        await ((IRepositorioModeracion)_fabrica.Persistencia).AbrirAsync(id, PersistenciaFalsa.EmpresaA, DateTimeOffset.UtcNow,
            IdCorrelacion.Nuevo(), CancellationToken.None);

        var bandeja = await (await Enviar(HttpMethod.Get, "/api/v1/moderacion/denuncias?estado=PENDIENTE", Operador()))
            .Content.ReadFromJsonAsync<JsonElement>();
        Assert.Contains(bandeja.GetProperty("elementos").EnumerateArray(), e => e.GetProperty("idDenuncia").GetGuid() == id);

        var r = await Enviar(HttpMethod.Post, $"/api/v1/moderacion/denuncias/{id}/decision", Operador(),
            new { decision = "VALIDADA", nota = "Verificado por la cuadrilla" });
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await Enviar(HttpMethod.Post, $"/api/v1/moderacion/denuncias/{id}/decision",
            Token(Rol.REGULADOR), new { decision = "DESCARTADA", nota = "Revisión" })).StatusCode);
    }
}
