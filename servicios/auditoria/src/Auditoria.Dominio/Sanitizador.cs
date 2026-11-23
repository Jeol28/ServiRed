using System.Text.Json;

namespace ServiRed.Auditoria.Dominio;

/// <summary>
/// Minimización de datos en la auditoría (RNF-SEG4, RNF-OBS2): el registro solo
/// guarda identificadores seudónimos y datos operativos. Se descartan los campos
/// que pueden contener datos personales o texto libre del ciudadano, a cualquier
/// profundidad, y las coordenadas exactas.
/// </summary>
public static class Sanitizador
{
    public static readonly IReadOnlySet<string> CamposProhibidos = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
    {
        "correo", "email", "nombre", "clave", "password", "contrasena", "hashClave", "token", "tokenAcceso",
        "tokenRefresco", "ip", "direccionIp", "userAgent", "descripcion", "comentario", "nota", "resolucion",
        "ubicacion", "latitud", "longitud", "telefono",
    };

    public static Dictionary<string, object?> Sanear(JsonElement elemento) =>
        elemento.ValueKind == JsonValueKind.Object ? Objeto(elemento) : new Dictionary<string, object?>();

    /// <summary>Copia solo los campos permitidos de los datos del evento.</summary>
    public static Dictionary<string, object?> Seleccionar(JsonElement datos, params string[] campos)
    {
        var resultado = new Dictionary<string, object?>();
        foreach (var campo in campos)
            if (!CamposProhibidos.Contains(campo) && datos.ValueKind == JsonValueKind.Object
                && datos.TryGetProperty(campo, out var v) && v.ValueKind != JsonValueKind.Null)
                resultado[campo] = Valor(v);
        return resultado;
    }

    private static Dictionary<string, object?> Objeto(JsonElement o)
    {
        var d = new Dictionary<string, object?>();
        foreach (var p in o.EnumerateObject())
            if (!CamposProhibidos.Contains(p.Name))
                d[p.Name] = Valor(p.Value);
        return d;
    }

    private static object? Valor(JsonElement v) => v.ValueKind switch
    {
        JsonValueKind.Object => Objeto(v),
        JsonValueKind.Array => v.EnumerateArray().Select(Valor).ToList(),
        JsonValueKind.String => v.GetString(),
        JsonValueKind.Number => v.TryGetInt64(out var l) ? l : v.GetDouble(),
        JsonValueKind.True => true,
        JsonValueKind.False => false,
        _ => null,
    };
}
