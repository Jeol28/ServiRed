using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
using ServiRed.Indicadores.Api.Correlacion;
using ServiRed.Indicadores.Api.Seguridad;
using ServiRed.Indicadores.Aplicacion;
using ServiRed.Indicadores.Dominio;

namespace ServiRed.Indicadores.Api.Endpoints;

/// <summary>
/// Portal público (RF-8 a RF-10, solo agregados y anonimizados), mapa operativo
/// del personal y exportación regulatoria. Todo lo público es cacheable 60 s.
/// </summary>
public static partial class EndpointsIndicadores
{
    public static void MapearIndicadores(this WebApplication app)
    {
        // ------------------------------------------------------------- públicos
        app.MapGet("/api/v1/indicadores/catalogo", (HttpContext h) =>
        {
            Publico(h);
            return Results.Ok(new { indicadores = CatalogoIndicadores.Todos, fuente = CatalogoIndicadores.Fuente,
                umbralSupresion = PoliticaAnonimizacion.UmbralMinimo, celdaMapaGrados = PoliticaAnonimizacion.CeldaGrados,
                // Fuera de las cabeceras municipales (ADR-28).
                umbralSupresionRural = PoliticaAnonimizacion.UmbralRural,
                celdaMapaRuralGrados = PoliticaAnonimizacion.CeldaRuralGrados });
        });

        app.MapGet("/api/v1/indicadores/resumen", async (HttpContext h, ConsultasIndicadores c, TimeProvider reloj,
            CancellationToken ct) =>
        {
            Publico(h);
            return Results.Ok(await c.ResumenAsync(Filtro(h, reloj), Correlacion(h), ct));
        });

        app.MapGet("/api/v1/indicadores/territorial", async (HttpContext h, ConsultasIndicadores c, TimeProvider reloj,
            NivelTerritorial? nivel, CancellationToken ct) =>
        {
            Publico(h);
            return Results.Ok(await c.TerritorialAsync(Filtro(h, reloj), nivel ?? NivelTerritorial.DEPARTAMENTO,
                Correlacion(h), ct));
        });

        app.MapGet("/api/v1/mapas/publico", async (HttpContext h, ConsultasIndicadores c, TimeProvider reloj,
            CancellationToken ct) =>
        {
            Publico(h);
            return Results.Ok(await c.MapaPublicoAsync(Filtro(h, reloj), Correlacion(h), ct));
        });

        app.MapGet("/api/v1/datos-abiertos/catalogo", async (HttpContext h, ConsultasIndicadores c, TimeProvider reloj,
            CancellationToken ct) =>
        {
            Publico(h);
            var metadatos = await c.MetadatosAsync(Filtro(h, reloj), Correlacion(h), ct);
            return Results.Ok(new
            {
                conjuntos = new[]
                {
                    new
                    {
                        id = "indicadores-municipales",
                        titulo = "Indicadores mensuales de interrupciones por municipio y servicio",
                        url = "/api/v1/datos-abiertos/indicadores-municipales?formato=csv",
                        formatos = new[] { "csv", "json" },
                        columnas = ColumnasDatosAbiertos,
                        licencia = "Uso académico; atribución a SERVI-RED",
                    },
                },
                metadatos,
            });
        });

        app.MapGet("/api/v1/datos-abiertos/indicadores-municipales", async (HttpContext h, ConsultasIndicadores c,
            TimeProvider reloj, string? formato, CancellationToken ct) =>
        {
            Publico(h);
            var datos = await c.DatosAbiertosAsync(Filtro(h, reloj), Correlacion(h), ct);
            if (!string.Equals(formato, "csv", StringComparison.OrdinalIgnoreCase)) return Results.Ok(datos);
            var csv = new StringBuilder().AppendLine(string.Join(',', ColumnasDatosAbiertos));
            foreach (var f in datos.Filas)
                Linea(csv, f.CodigoMunicipio, f.Municipio, f.Departamento, f.TipoServicio, f.Mes, f.Reportes,
                    f.DuracionMedianaMinutos, f.IndiceSeveridad, f.PorcentajeResueltos, f.Suprimido);
            return Csv(h, csv, "servired-indicadores-municipales.csv");
        });

        // ------------------------------------------------------------- personal
        app.MapGet("/api/v1/mapas/operativo", async (HttpContext h, ConsultasIndicadores c, TimeProvider reloj,
                CancellationToken ct) =>
            Results.Ok(await c.MapaOperativoAsync(ActorActual.De(h), Filtro(h, reloj, permitirEmpresa: true),
                Correlacion(h), ct)))
            .RequireAuthorization(ConfiguracionJwt.PoliticaPersonal);

        app.MapGet("/api/v1/indicadores/exportaciones/reportes", async (HttpContext h, ConsultasIndicadores c,
                TimeProvider reloj, ILogger<ConsultasIndicadores> log, string? formato, CancellationToken ct) =>
            {
                var actor = ActorActual.De(h);
                var filtro = Filtro(h, reloj, permitirEmpresa: true);
                var filas = new List<Hecho>();
                await foreach (var hecho in c.ExportarAsync(actor, filtro, Correlacion(h), ct)) filas.Add(hecho);
                log.LogInformation("exportación regulatoria de {Filas} reportes por {Cuenta} ({Rol})",
                    filas.Count, actor.IdCuenta, actor.Rol);
                if (!string.Equals(formato, "csv", StringComparison.OrdinalIgnoreCase))
                    return Results.Ok(new { periodo = filtro.Periodo, reportes = filas });
                var csv = new StringBuilder().AppendLine(string.Join(',', ColumnasExportacion));
                foreach (var f in filas)
                    Linea(csv, f.IdReporte, f.TipoServicio, f.CodigoMunicipio, f.NombreMunicipio, f.Departamento,
                        f.Latitud, f.Longitud, f.Magnitud, f.InicioEstimado, f.FechaRegistro, f.FechaRestablecimiento,
                        f.DuracionMinutos, f.Validado, f.ConfirmacionesNetas, f.EstadoCaso, f.Prioridad, f.IdEmpresa,
                        f.ResueltoEn, f.CerradoEn);
                h.Response.Headers.CacheControl = "no-store";
                return Csv(h, csv, "servired-reportes-regulatorio.csv");
            })
            .RequireAuthorization(ConfiguracionJwt.PoliticaExportar);
    }

    public static readonly string[] ColumnasDatosAbiertos =
    [
        "codigo_municipio", "municipio", "departamento", "tipo_servicio", "mes", "reportes",
        "duracion_mediana_minutos", "indice_severidad", "porcentaje_resueltos", "suprimido",
    ];

    public static readonly string[] ColumnasExportacion =
    [
        "id_reporte", "tipo_servicio", "codigo_municipio", "municipio", "departamento", "latitud", "longitud",
        "magnitud", "inicio_estimado", "fecha_registro", "fecha_restablecimiento", "duracion_minutos", "validado",
        "confirmaciones_netas", "estado_caso", "prioridad", "id_empresa", "resuelto_en", "cerrado_en",
    ];

    /// <summary>Filtros comunes: desde, hasta, tipoServicio, codigoDepartamento, codigoMunicipio, situacion.</summary>
    private static FiltroVista Filtro(HttpContext h, TimeProvider reloj, bool permitirEmpresa = false)
    {
        var q = h.Request.Query;
        var periodo = Periodo.Crear(FechaOpcional(q["desde"], "desde"), FechaOpcional(q["hasta"], "hasta"), reloj.GetUtcNow());
        string? Codigo(string campo, Regex forma) =>
            q[campo].ToString() is { Length: > 0 } v
                ? forma.IsMatch(v) ? v : throw new DatosInvalidos(campo, "formato DIVIPOLA inválido")
                : null;
        TEnum? Enumeracion<TEnum>(string campo) where TEnum : struct, Enum =>
            q[campo].ToString() is { Length: > 0 } v
                ? Enum.TryParse<TEnum>(v, ignoreCase: false, out var e) ? e : throw new DatosInvalidos(campo, "valor no permitido")
                : null;
        Guid? empresa = permitirEmpresa && Guid.TryParse(q["idEmpresa"], out var g) ? g : null;
        return new FiltroVista(periodo, Enumeracion<TipoServicio>("tipoServicio"), Codigo("codigoDepartamento", Departamento()),
            Codigo("codigoMunicipio", Municipio()), empresa, Enumeracion<Situacion>("situacion"));
    }

    private static DateTimeOffset? FechaOpcional(string? valor, string campo) =>
        string.IsNullOrWhiteSpace(valor) ? null
        : DateTimeOffset.TryParse(valor, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var f) ? f
        : throw new DatosInvalidos(campo, "fecha ISO 8601 inválida");

    private static void Publico(HttpContext h) => h.Response.Headers.CacheControl = "public, max-age=60";

    private static IdCorrelacion Correlacion(HttpContext h) => (IdCorrelacion)h.Items[MiddlewareCorrelacion.ClaveItem]!;

    private static IResult Csv(HttpContext h, StringBuilder csv, string archivo)
    {
        h.Response.Headers.ContentDisposition = $"attachment; filename=\"{archivo}\"";
        return Results.Text(csv.ToString(), "text/csv; charset=utf-8", Encoding.UTF8);
    }

    private static void Linea(StringBuilder csv, params object?[] valores) =>
        csv.AppendLine(string.Join(',', valores.Select(Celda)));

    /// <summary>CSV RFC 4180; además neutraliza fórmulas (=, +, -, @) para hojas de cálculo.</summary>
    private static string Celda(object? v)
    {
        var texto = v switch
        {
            null => "",
            DateTimeOffset d => d.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ", CultureInfo.InvariantCulture),
            bool b => b ? "true" : "false",
            IFormattable f => f.ToString(null, CultureInfo.InvariantCulture),
            _ => v.ToString() ?? "",
        };
        if (v is string && texto.Length > 0 && "=+-@".Contains(texto[0])) texto = "'" + texto;
        return texto.IndexOfAny([',', '"', '\n', '\r']) >= 0 ? "\"" + texto.Replace("\"", "\"\"") + "\"" : texto;
    }

    [GeneratedRegex("^[0-9]{2}$")]
    private static partial Regex Departamento();

    [GeneratedRegex("^[0-9]{5}$")]
    private static partial Regex Municipio();
}
