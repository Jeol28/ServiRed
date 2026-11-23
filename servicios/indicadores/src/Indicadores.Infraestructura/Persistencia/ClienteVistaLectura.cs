using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Serialization;
using ServiRed.Indicadores.Aplicacion;
using ServiRed.Indicadores.Dominio;

namespace ServiRed.Indicadores.Infraestructura.Persistencia;

/// <summary>
/// Adaptador hacia la vista de lectura en Persistencia (contratos/internos/persistencia.v1.yaml).
/// 5xx o error de red: transitorio (HttpRequestException). 4xx: falla permanente.
/// </summary>
public sealed class ClienteVistaLectura(HttpClient http) : IVistaLectura, IRegistroAcciones
{
    public const string CabeceraClave = "X-Clave-Servicio";

    internal static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    {
        Converters = { new JsonStringEnumConverter() },
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    public Task ActualizarAsync(ActualizacionVista a, IdCorrelacion correlacion, CancellationToken ct) =>
        EnviarAsync<object>(HttpMethod.Post, "/interno/v1/indicadores/vista", a, correlacion, ct, sinCuerpo: true);

    public async Task<IReadOnlyList<Agregado>> AgregarAsync(FiltroVista filtro, Agrupacion agrupacion, double celdaGrados,
        double celdaRuralGrados, IdCorrelacion correlacion, CancellationToken ct) =>
        (await EnviarAsync<List<Agregado>>(HttpMethod.Post, "/interno/v1/indicadores/agregados",
            new
            {
                Filtro = Filtro(filtro), Agrupacion = agrupacion.ToString(),
                CeldaGrados = celdaGrados > 0 ? celdaGrados : (double?)null,
                CeldaRuralGrados = celdaRuralGrados > 0 ? celdaRuralGrados : (double?)null,
            },
            correlacion, ct))!;

    public async Task<PaginaHechos> HechosAsync(FiltroVista filtro, int desde, int limite, IdCorrelacion correlacion,
        CancellationToken ct) =>
        (await EnviarAsync<PaginaHechos>(HttpMethod.Post, "/interno/v1/indicadores/hechos",
            new { Filtro = Filtro(filtro), Desde = desde, Limite = limite }, correlacion, ct))!;

    public async Task<EstadoVista> EstadoAsync(IdCorrelacion correlacion, CancellationToken ct) =>
        (await EnviarAsync<EstadoVista>(HttpMethod.Get, "/interno/v1/indicadores/estado", null, correlacion, ct))!;

    public Task PublicarAsync(AccionAuditada a, IdCorrelacion correlacion, CancellationToken ct) =>
        EnviarAsync<object>(HttpMethod.Post, "/interno/v1/acciones-auditadas", SobreAccionAuditada(a, correlacion),
            correlacion, ct, sinCuerpo: true);

    /// <summary>Sobre del evento accion.auditada v1 (contratos/eventos/esquemas/accion.auditada.v1.json).</summary>
    internal static Dictionary<string, object?> SobreAccionAuditada(AccionAuditada a, IdCorrelacion correlacion) =>
        new()
        {
            ["idEvento"] = Guid.NewGuid(),
            ["tipo"] = "accion.auditada",
            ["version"] = 1,
            ["fechaOcurrencia"] = DateTimeOffset.UtcNow,
            ["correlationId"] = Guid.Parse(correlacion.Valor),
            ["origen"] = "indicadores",
            ["datos"] = new Dictionary<string, object?>
            {
                ["categoria"] = a.Categoria,
                ["accion"] = a.Accion,
                ["resultado"] = "EXITO",
                ["actor"] = new Dictionary<string, object?> { ["idCuenta"] = a.Actor.IdCuenta, ["rol"] = a.Actor.Rol.ToString() },
                ["recurso"] = new Dictionary<string, object?> { ["tipo"] = a.RecursoTipo },
                ["detalle"] = a.Detalle,
            },
        };

    private static object Filtro(FiltroVista f) => new
    {
        f.Periodo.Desde, f.Periodo.Hasta, TipoServicio = f.TipoServicio?.ToString(), f.CodigoDepartamento,
        f.CodigoMunicipio, f.IdEmpresa, Situacion = f.Situacion?.ToString(),
    };

    private async Task<T?> EnviarAsync<T>(HttpMethod metodo, string ruta, object? cuerpo, IdCorrelacion correlacion,
        CancellationToken ct, bool sinCuerpo = false)
    {
        using var peticion = new HttpRequestMessage(metodo, ruta);
        peticion.Headers.Add(IdCorrelacion.Cabecera, correlacion.Valor);
        if (cuerpo is not null) peticion.Content = JsonContent.Create(cuerpo, options: Json);
        using var respuesta = await http.SendAsync(peticion, ct);
        if (respuesta.IsSuccessStatusCode)
            return sinCuerpo ? default : await respuesta.Content.ReadFromJsonAsync<T>(Json, ct);
        var codigo = (int)respuesta.StatusCode;
        if (respuesta.StatusCode == HttpStatusCode.Conflict)
            throw new DatosInvalidos("periodo", "Filtro rechazado por la vista de lectura");
        if (codigo is >= 400 and < 500)
            throw new FallaPermanente($"Persistencia rechazó la operación con {codigo}");
        throw new HttpRequestException($"Persistencia respondió {codigo}", null, respuesta.StatusCode);
    }
}
