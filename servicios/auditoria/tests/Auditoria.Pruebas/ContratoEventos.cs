using System.Text.Json;
using System.Text.Json.Nodes;
using Json.Schema;

namespace ServiRed.Auditoria.Pruebas;

/// <summary>
/// Valida eventos contra el contrato del repositorio (contratos/eventos): el sobre común y
/// el esquema de los datos de su tipo y versión (JSON Schema 2020-12), con los formatos uuid
/// y date-time comprobados. Es el mismo contrato que validan los servicios Java.
/// </summary>
public static class ContratoEventos
{
    public static readonly string Eventos = BuscarContrato();

    private static readonly EvaluationOptions Opciones = new()
    {
        OutputFormat = OutputFormat.List,
        RequireFormatValidation = true,
    };

    private static readonly Dictionary<string, JsonSchema> Esquemas = Directory
        .GetFiles(Path.Combine(Eventos, "esquemas"), "*.json")
        .ToDictionary(f => Path.GetFileName(f), f => JsonSchema.FromText(File.ReadAllText(f)));

    /// <summary>Errores del sobre frente al contrato; vacío si lo cumple.</summary>
    public static List<string> Errores(JsonNode sobre, string? routingKey)
    {
        var errores = Evaluar("sobre-evento.v1.json", sobre).Select(e => "sobre: " + e).ToList();
        if (errores.Count > 0) return errores;
        var esperado = $"{sobre["tipo"]!.GetValue<string>()}.v{sobre["version"]!.GetValue<int>()}";
        if (routingKey is not null && routingKey != esperado)
            errores.Add($"routing key {routingKey}; el contrato dice {esperado}");
        if (!Esquemas.ContainsKey(esperado + ".json"))
        {
            errores.Add($"el contrato no define {esperado}");
            return errores;
        }
        errores.AddRange(Evaluar(esperado + ".json", sobre["datos"]).Select(e => "datos: " + e));
        return errores;
    }

    public static List<string> Errores(string json, string? routingKey) => Errores(JsonNode.Parse(json)!, routingKey);

    /// <summary>Ejemplo del contrato: contratos/eventos/ejemplos/{tipo}.v1.{completo|minimo}.json.</summary>
    public static string Ejemplo(string tipo, string variante) =>
        File.ReadAllText(Path.Combine(Eventos, "ejemplos", $"{tipo}.v1.{variante}.json"));

    public static TheoryData<string> Variantes => new() { "completo", "minimo" };

    private static IEnumerable<string> Evaluar(string archivo, JsonNode? instancia)
    {
        var resultado = Esquemas[archivo].Evaluate(instancia, Opciones);
        if (resultado.IsValid) return [];
        return resultado.Details.Where(d => d.HasErrors)
            .SelectMany(d => d.Errors!.Select(e => $"{d.InstanceLocation}: {e.Value}"));
    }

    private static string BuscarContrato()
    {
        for (var dir = new DirectoryInfo(AppContext.BaseDirectory); dir is not null; dir = dir.Parent)
        {
            var candidato = Path.Combine(dir.FullName, "contratos", "eventos");
            if (Directory.Exists(candidato)) return candidato;
        }
        throw new DirectoryNotFoundException("no se encontró contratos/eventos subiendo desde " + AppContext.BaseDirectory);
    }
}
