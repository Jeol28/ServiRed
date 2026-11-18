using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Serialization;
using ServiRed.Atencion.Aplicacion;
using ServiRed.Atencion.Aplicacion.Puertos;
using ServiRed.Atencion.Dominio;

namespace ServiRed.Atencion.Infraestructura.Persistencia;

/// <summary>
/// Adaptador hacia la API interna de Persistencia (contratos/internos/persistencia.v1.yaml).
/// 5xx, timeout o error de red son transitorios (HttpRequestException); un 4xx
/// inesperado es una falla permanente de integración.
/// </summary>
public sealed class ClientePersistencia(HttpClient http) : IRepositorioCasos, ICatalogoAtencion, IRepositorioModeracion
{
    public const string CabeceraClave = "X-Clave-Servicio";

    internal static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    {
        Converters = { new JsonStringEnumConverter() },
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    // ------------------------------------------------------------------ casos
    public async Task<Caso> AbrirAsync(Caso caso, IdCorrelacion correlacion, CancellationToken ct)
    {
        var cuerpo = new
        {
            caso.IdCaso, caso.IdReporte, TipoServicio = caso.TipoServicio.ToString(), caso.CodigoMunicipio,
            Magnitud = caso.Magnitud.ToString(), caso.AbiertoEn, caso.IdEmpresa, Prioridad = caso.Prioridad.ToString(),
            caso.Latitud, caso.Longitud, caso.Descripcion, caso.InicioEstimado,
        };
        return (await EnviarAsync<Caso>(HttpMethod.Post, "/interno/v1/casos/abrir", cuerpo, correlacion, ct))!;
    }

    public Task<Caso?> ConsultarAsync(Guid idCaso, IdCorrelacion correlacion, CancellationToken ct) =>
        EnviarAsync<Caso>(HttpMethod.Get, $"/interno/v1/casos/{idCaso}", null, correlacion, ct, permitir404: true);

    public async Task<Caso?> BuscarPorReporteAsync(Guid idReporte, IdCorrelacion correlacion, CancellationToken ct)
    {
        var pagina = await ListarAsync(new FiltroCasos(IdReporte: idReporte, Tamano: 1), correlacion, ct);
        return pagina.Elementos.Count == 0 ? null : await ConsultarAsync(pagina.Elementos[0].IdCaso, correlacion, ct);
    }

    public async Task<Pagina<Caso>> ListarAsync(FiltroCasos f, IdCorrelacion correlacion, CancellationToken ct)
    {
        var parametros = new List<string> { $"pagina={f.Pagina}", $"tamano={f.Tamano}" };
        void Agregar(string nombre, object? valor)
        {
            if (valor is not null) parametros.Add($"{nombre}={Uri.EscapeDataString(valor.ToString()!)}");
        }
        Agregar("estado", f.Estado);
        Agregar("tipoServicio", f.TipoServicio);
        Agregar("codigoMunicipio", f.CodigoMunicipio);
        Agregar("codigoDepartamento", f.CodigoDepartamento);
        Agregar("prioridad", f.Prioridad);
        Agregar("idEmpresa", f.IdEmpresa);
        Agregar("asignadoA", f.AsignadoA);
        Agregar("idReporte", f.IdReporte);
        if (f.SoloSinEmpresa) parametros.Add("sinEmpresa=true");
        var p = await EnviarAsync<PaginaDto<Caso>>(HttpMethod.Get, "/interno/v1/casos?" + string.Join('&', parametros),
            null, correlacion, ct);
        return new Pagina<Caso>(p!.Elementos, p.Total, p.Pagina, p.Tamano);
    }

    public async Task<Caso> ActualizarAsync(Guid idCaso, CambiosCaso c, IReadOnlyList<SobreEventoSaliente> eventos,
        IdCorrelacion correlacion, CancellationToken ct)
    {
        var cuerpo = new
        {
            c.VersionEsperada, Estado = c.Estado?.ToString(), Prioridad = c.Prioridad?.ToString(), c.PrioridadManual,
            c.AsignadoA, c.IdEmpresa, c.CorteValidado, c.Resolucion, c.ResueltoEn, c.CerradoEn,
            DetalleHistoria = c.Detalle, c.Autor, c.Fecha, Eventos = eventos,
        };
        return (await EnviarAsync<Caso>(HttpMethod.Post, $"/interno/v1/casos/{idCaso}/actualizar", cuerpo, correlacion, ct))!;
    }

    public Task<Archivo?> EvidenciaAsync(Guid idCaso, Guid idEvidencia, IdCorrelacion correlacion, CancellationToken ct) =>
        BytesAsync($"/interno/v1/casos/{idCaso}/evidencias/{idEvidencia}", correlacion, ct);

    // --------------------------------------------------------------- catálogo
    public async Task<IReadOnlyList<Guid>> EmpresasConCoberturaAsync(string codigoMunicipio, TipoServicio servicio,
        IdCorrelacion correlacion, CancellationToken ct)
    {
        var empresas = await EnviarAsync<List<EmpresaDto>>(HttpMethod.Get,
            $"/interno/v1/catalogo/cobertura?codigoMunicipio={codigoMunicipio}&tipoServicio={servicio}", null, correlacion, ct);
        return empresas!.Select(e => e.IdEmpresa).ToList();
    }

    public async Task<bool> EsOperadorDeAsync(Guid idCuenta, Guid idEmpresa, IdCorrelacion correlacion, CancellationToken ct)
    {
        var r = await EnviarAsync<Dictionary<string, bool>>(HttpMethod.Get,
            $"/interno/v1/operadores/{idCuenta}?idEmpresa={idEmpresa}", null, correlacion, ct);
        return r!.GetValueOrDefault("operadorDeLaEmpresa");
    }

    // ------------------------------------------------------------- moderación
    async Task<Moderacion> IRepositorioModeracion.AbrirAsync(Guid idDenuncia, Guid? idEmpresa, DateTimeOffset recibidaEn,
        IdCorrelacion correlacion, CancellationToken ct) =>
        (await EnviarAsync<Moderacion>(HttpMethod.Post, "/interno/v1/moderaciones/abrir",
            new { IdDenuncia = idDenuncia, IdEmpresa = idEmpresa, RecibidaEn = recibidaEn }, correlacion, ct))!;

    Task<Moderacion?> IRepositorioModeracion.ConsultarAsync(Guid idDenuncia, IdCorrelacion correlacion, CancellationToken ct) =>
        EnviarAsync<Moderacion>(HttpMethod.Get, $"/interno/v1/moderaciones/{idDenuncia}", null, correlacion, ct,
            permitir404: true);

    async Task<Pagina<Moderacion>> IRepositorioModeracion.ListarAsync(EstadoModeracion? estado, Guid? idEmpresa,
        int pagina, int tamano, IdCorrelacion correlacion, CancellationToken ct)
    {
        var url = $"/interno/v1/moderaciones?pagina={pagina}&tamano={tamano}"
                  + (estado is null ? "" : $"&estado={estado}") + (idEmpresa is null ? "" : $"&idEmpresa={idEmpresa}");
        var p = await EnviarAsync<PaginaDto<Moderacion>>(HttpMethod.Get, url, null, correlacion, ct);
        return new Pagina<Moderacion>(p!.Elementos, p.Total, p.Pagina, p.Tamano);
    }

    async Task<Moderacion> IRepositorioModeracion.DecidirAsync(Guid idDenuncia, Decision d,
        IReadOnlyList<SobreEventoSaliente> eventos, IdCorrelacion correlacion, CancellationToken ct) =>
        (await EnviarAsync<Moderacion>(HttpMethod.Post, $"/interno/v1/moderaciones/{idDenuncia}/decidir",
            new { d.VersionEsperada, Estado = d.Estado.ToString(), d.Nota, d.ModeradaPor, d.Fecha, Eventos = eventos },
            correlacion, ct))!;

    Task<Archivo?> IRepositorioModeracion.EvidenciaAsync(Guid idDenuncia, Guid idEvidencia, IdCorrelacion correlacion,
        CancellationToken ct) =>
        BytesAsync($"/interno/v1/denuncias/{idDenuncia}/evidencias/{idEvidencia}", correlacion, ct);

    // ------------------------------------------------------------- auxiliares
    private async Task<T?> EnviarAsync<T>(HttpMethod metodo, string ruta, object? cuerpo, IdCorrelacion correlacion,
        CancellationToken ct, bool permitir404 = false)
    {
        using var peticion = new HttpRequestMessage(metodo, ruta);
        peticion.Headers.Add(IdCorrelacion.Cabecera, correlacion.Valor);
        if (cuerpo is not null) peticion.Content = JsonContent.Create(cuerpo, options: Json);
        using var respuesta = await http.SendAsync(peticion, ct);
        if (respuesta.IsSuccessStatusCode)
            return await respuesta.Content.ReadFromJsonAsync<T>(Json, ct);
        await Fallar(respuesta, permitir404, ct);
        return default;
    }

    private async Task<Archivo?> BytesAsync(string ruta, IdCorrelacion correlacion, CancellationToken ct)
    {
        using var peticion = new HttpRequestMessage(HttpMethod.Get, ruta);
        peticion.Headers.Add(IdCorrelacion.Cabecera, correlacion.Valor);
        using var respuesta = await http.SendAsync(peticion, ct);
        if (respuesta.IsSuccessStatusCode)
            return new Archivo(respuesta.Content.Headers.ContentType?.MediaType ?? "application/octet-stream",
                await respuesta.Content.ReadAsByteArrayAsync(ct));
        await Fallar(respuesta, permitir404: true, ct);
        return null;
    }

    private static async Task Fallar(HttpResponseMessage respuesta, bool permitir404, CancellationToken ct)
    {
        var codigo = (int)respuesta.StatusCode;
        if (respuesta.StatusCode == HttpStatusCode.NotFound)
        {
            if (permitir404) return;
            throw new NoEncontrado("El recurso no existe");
        }
        if (respuesta.StatusCode == HttpStatusCode.Conflict)
        {
            var cuerpo = await respuesta.Content.ReadAsStringAsync(ct);
            // ADR-23: la correlación de otra solicitud no es un reintento ni un conflicto de versión.
            if (cuerpo.Contains("\"CORRELACION_AJENA\""))
                throw new ReglaIncumplida("CORRELACION_AJENA", "El X-Correlation-Id ya se usó en otra solicitud: envíe uno nuevo");
            if (cuerpo.Contains("\"VERSION\"")) throw new ConflictoDeVersion();
            throw new FallaPermanente("Persistencia rechazó la operación (409)");
        }
        if (codigo is >= 400 and < 500)
            throw new FallaPermanente($"Persistencia rechazó la operación con {codigo}");
        throw new HttpRequestException($"Persistencia respondió {codigo}", null, respuesta.StatusCode);
    }

    private sealed record EmpresaDto(Guid IdEmpresa, string Nombre);

    private sealed record PaginaDto<T>(List<T> Elementos, long Total, int Pagina, int Tamano);
}
