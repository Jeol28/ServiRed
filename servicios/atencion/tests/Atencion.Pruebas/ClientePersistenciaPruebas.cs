using System.Net;
using System.Text;
using ServiRed.Atencion.Aplicacion;
using ServiRed.Atencion.Dominio;
using ServiRed.Atencion.Infraestructura.Persistencia;

namespace ServiRed.Atencion.Pruebas;

public class ClientePersistenciaPruebas
{
    private sealed class ManejadorFalso(Func<HttpRequestMessage, HttpResponseMessage> responder) : HttpMessageHandler
    {
        public HttpRequestMessage? Ultima;
        public string? Cuerpo;

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage peticion, CancellationToken ct)
        {
            Ultima = peticion;
            Cuerpo = peticion.Content is null ? null : await peticion.Content.ReadAsStringAsync(ct);
            return responder(peticion);
        }
    }

    private static readonly Caso Caso = Caso.AbrirDesdeReporte(Guid.NewGuid(), TipoServicio.ENERGIA, "11001",
        Magnitud.CUADRA, DateTimeOffset.Parse("2026-11-14T15:00:00Z"));

    private static (ClientePersistencia, ManejadorFalso) Cliente(HttpStatusCode codigo, string cuerpo = "")
    {
        var manejador = new ManejadorFalso(_ => new HttpResponseMessage(codigo)
        {
            Content = new StringContent(cuerpo, Encoding.UTF8, "application/json"),
        });
        var http = new HttpClient(manejador) { BaseAddress = new Uri("http://persistencia:8080") };
        http.DefaultRequestHeaders.Add(ClientePersistencia.CabeceraClave, "clave-atencion-123456");
        return (new ClientePersistencia(http), manejador);
    }

    [Fact]
    public async Task EnviaLaOperacionAbrirCasoConClaveYCorrelacion()
    {
        var respuesta = $$"""
            {"idCaso":"{{Caso.IdCaso}}","idReporte":"{{Caso.IdReporte}}","tipoServicio":"ENERGIA",
             "codigoMunicipio":"11001","magnitud":"CUADRA","estado":"RECIBIDO","abiertoEn":"2026-11-14T15:00:00Z"}
            """;
        var (cliente, manejador) = Cliente(HttpStatusCode.Created, respuesta);
        var correlacion = IdCorrelacion.Nuevo();

        var caso = await cliente.AbrirAsync(Caso, correlacion, CancellationToken.None);

        Assert.Equal(Caso.IdCaso, caso.IdCaso);
        Assert.Equal(EstadoCaso.RECIBIDO, caso.Estado);
        Assert.Equal("/interno/v1/casos/abrir", manejador.Ultima!.RequestUri!.AbsolutePath);
        Assert.Equal(correlacion.Valor, manejador.Ultima.Headers.GetValues(IdCorrelacion.Cabecera).Single());
        Assert.Contains("\"tipoServicio\":\"ENERGIA\"", manejador.Cuerpo);
        Assert.Contains("\"codigoMunicipio\":\"11001\"", manejador.Cuerpo);
    }

    [Theory]
    [InlineData(HttpStatusCode.Conflict)]
    [InlineData(HttpStatusCode.Forbidden)]
    public async Task UnRechazoEsUnaFallaPermanente(HttpStatusCode codigo)
    {
        var (cliente, _) = Cliente(codigo);

        await Assert.ThrowsAsync<FallaPermanente>(() => cliente.AbrirAsync(Caso, IdCorrelacion.Nuevo(), CancellationToken.None));
    }

    [Fact]
    public async Task Un5xxEsTransitorio()
    {
        var (cliente, _) = Cliente(HttpStatusCode.ServiceUnavailable);

        await Assert.ThrowsAsync<HttpRequestException>(() => cliente.AbrirAsync(Caso, IdCorrelacion.Nuevo(), CancellationToken.None));
    }
}
