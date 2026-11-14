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
/// Un 4xx es una falla permanente (no se reintenta); un 5xx, timeout o error de
/// red es transitorio y el consumidor lo reintenta vía la cola de reintento.
/// </summary>
public sealed class ClientePersistencia(HttpClient http) : IRepositorioCasos
{
    public const string CabeceraClave = "X-Clave-Servicio";

    internal static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    {
        Converters = { new JsonStringEnumConverter() },
    };

    public async Task<Caso> AbrirAsync(Caso caso, IdCorrelacion correlacion, CancellationToken ct)
    {
        using var peticion = new HttpRequestMessage(HttpMethod.Post, "/interno/v1/casos/abrir")
        {
            Content = JsonContent.Create(new
            {
                caso.IdCaso,
                caso.IdReporte,
                TipoServicio = caso.TipoServicio.ToString(),
                caso.CodigoMunicipio,
                Magnitud = caso.Magnitud.ToString(),
                caso.AbiertoEn,
            }, options: Json),
        };
        peticion.Headers.Add(IdCorrelacion.Cabecera, correlacion.Valor);

        using var respuesta = await http.SendAsync(peticion, ct);
        if (respuesta.StatusCode is HttpStatusCode.Created or HttpStatusCode.OK)
        {
            return await respuesta.Content.ReadFromJsonAsync<Caso>(Json, ct)
                   ?? throw new InvalidOperationException("Persistencia respondió sin cuerpo");
        }
        var codigo = (int)respuesta.StatusCode;
        if (codigo is >= 400 and < 500)
            throw new FallaPermanente($"Persistencia rechazó abrirCaso con {codigo}");
        throw new HttpRequestException($"Persistencia respondió {codigo}", null, respuesta.StatusCode);
    }
}
