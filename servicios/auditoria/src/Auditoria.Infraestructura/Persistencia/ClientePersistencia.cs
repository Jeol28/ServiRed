using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Serialization;
using ServiRed.Auditoria.Aplicacion;
using ServiRed.Auditoria.Dominio;

namespace ServiRed.Auditoria.Infraestructura.Persistencia;

/// <summary>
/// Adaptador hacia el registro de auditoría en Persistencia. 5xx o red: transitorio
/// (HttpRequestException, el consumidor reintenta). 4xx: falla permanente.
/// </summary>
public sealed class ClientePersistencia(HttpClient http) : IRepositorioAuditoria
{
    public const string CabeceraClave = "X-Clave-Servicio";

    internal static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    {
        Converters = { new JsonStringEnumConverter() },
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    public async Task<bool> RegistrarAsync(RegistroAuditoria r, IdCorrelacion correlacion, CancellationToken ct)
    {
        var respuesta = await EnviarAsync<Dictionary<string, bool>>(HttpMethod.Post, "/interno/v1/auditoria/registros", new
        {
            r.IdEvento, r.Tipo, r.Version, r.Origen, r.CorrelationId, r.Categoria, r.Accion, r.Resultado, r.ActorId,
            r.ActorRol, r.RecursoTipo, r.RecursoId, r.Detalle, r.OcurridoEn,
        }, correlacion, ct);
        return respuesta!.GetValueOrDefault("nuevo");
    }

    public async Task<PaginaAuditoria> ConsultarAsync(FiltroAuditoria f, IdCorrelacion correlacion, CancellationToken ct)
    {
        var q = new List<string> { $"pagina={f.Pagina}", $"tamano={f.Tamano}" };
        void Agregar(string n, object? v)
        {
            if (v is not null) q.Add($"{n}={Uri.EscapeDataString(v is DateTimeOffset d ? d.ToString("O") : v.ToString()!)}");
        }
        Agregar("categoria", f.Categoria);
        Agregar("accion", f.Accion);
        Agregar("actorId", f.ActorId);
        Agregar("recursoId", f.RecursoId);
        Agregar("actorRol", f.ActorRol);
        Agregar("recursoTipo", f.RecursoTipo);
        Agregar("desde", f.Desde);
        Agregar("hasta", f.Hasta);
        var p = await EnviarAsync<PaginaDto>(HttpMethod.Get, "/interno/v1/auditoria/registros?" + string.Join('&', q), null,
            correlacion, ct);
        return new PaginaAuditoria(p!.Elementos.Select(e => e with
        {
            Detalle = e.DetalleTexto is null ? null : JsonDocument.Parse(e.DetalleTexto).RootElement,
        }).Select(e => new RegistroConsultado(e.Secuencia, e.IdEvento, e.Tipo, e.Version, e.Origen, e.CorrelationId,
            e.Categoria, e.Accion, e.Resultado, e.ActorId, e.ActorRol, e.RecursoTipo, e.RecursoId, e.Detalle, e.OcurridoEn,
            e.RegistradoEn, e.Hash)).ToList(), p.Total, p.Pagina, p.Tamano);
    }

    public async Task<Verificacion> VerificarAsync(IdCorrelacion correlacion, CancellationToken ct) =>
        (await EnviarAsync<Verificacion>(HttpMethod.Get, "/interno/v1/auditoria/integridad", null, correlacion, ct))!;

    public async Task<IReadOnlyList<AnclaPublica>> AnclasAsync(IdCorrelacion correlacion, CancellationToken ct) =>
        (await EnviarAsync<List<AnclaPublica>>(HttpMethod.Get, "/interno/v1/auditoria/anclas", null, correlacion, ct))!;

    private async Task<T?> EnviarAsync<T>(HttpMethod metodo, string ruta, object? cuerpo, IdCorrelacion correlacion,
        CancellationToken ct)
    {
        using var peticion = new HttpRequestMessage(metodo, ruta);
        peticion.Headers.Add(IdCorrelacion.Cabecera, correlacion.Valor);
        if (cuerpo is not null) peticion.Content = JsonContent.Create(cuerpo, options: Json);
        using var respuesta = await http.SendAsync(peticion, ct);
        if (respuesta.IsSuccessStatusCode) return await respuesta.Content.ReadFromJsonAsync<T>(Json, ct);
        var codigo = (int)respuesta.StatusCode;
        if (codigo is >= 400 and < 500) throw new FallaPermanente($"Persistencia rechazó la operación con {codigo}");
        throw new HttpRequestException($"Persistencia respondió {codigo}", null, respuesta.StatusCode);
    }

    private sealed record FilaDto(long Secuencia, Guid IdEvento, string Tipo, int Version, string Origen,
        Guid? CorrelationId, string Categoria, string Accion, string? Resultado, Guid? ActorId, string? ActorRol,
        string? RecursoTipo, string? RecursoId, [property: JsonPropertyName("detalle")] string? DetalleTexto,
        DateTimeOffset OcurridoEn, DateTimeOffset RegistradoEn, string Hash)
    {
        [JsonIgnore] public JsonElement? Detalle { get; init; }
    }

    private sealed record PaginaDto(List<FilaDto> Elementos, long Total, int Pagina, int Tamano);
}
