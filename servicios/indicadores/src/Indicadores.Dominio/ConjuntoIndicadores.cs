namespace ServiRed.Indicadores.Dominio;

/// <summary>Definición de un indicador (RNF-DAT4): qué mide, cómo se calcula y de dónde sale.</summary>
public sealed record DefinicionIndicador(string Id, string Nombre, string Definicion, string Unidad, string Fuente);

/// <summary>Catálogo de indicadores de RF-9 publicado con cada respuesta.</summary>
public static class CatalogoIndicadores
{
    public const string Fuente =
        "Reportes ciudadanos de SERVI-RED y su atención por las empresas prestadoras (eventos reporte.creado, " +
        "restablecimiento.registrado, validacion.registrada y caso.*). Un reporte es evidencia inicial, no confirmación.";

    public static readonly IReadOnlyList<DefinicionIndicador> Todos =
    [
        new("frecuencia", "Frecuencia de interrupciones",
            "Número de reportes de corte no programado registrados en el periodo.", "reportes", Fuente),
        new("duracion", "Duración de las interrupciones",
            "Minutos entre el inicio estimado del corte y el restablecimiento: promedio, mediana y percentil 90 de los reportes restablecidos.",
            "minutos", Fuente),
        new("severidad", "Severidad",
            "Índice de 1 a 4 = promedio de la magnitud reportada (VIVIENDA 1, CUADRA 2, BARRIO 3, SECTOR 4), más la distribución por magnitud y el porcentaje de cortes validados por la comunidad.",
            "índice 1–4 y %", Fuente),
        new("atencion", "Atención y resolución",
            "Porcentaje de reportes restablecidos o resueltos y tiempo promedio entre el registro y la resolución del caso.",
            "% y minutos", Fuente),
        new("distribucion", "Distribución geográfica",
            "Los indicadores anteriores por departamento y municipio (DIVIPOLA) y, en el mapa, por celdas de ~1,1 km.",
            "por territorio", Fuente),
    ];
}

public sealed record Duracion(double? Promedio, double? Mediana, double? P90, long ReportesConDuracion);

public sealed record Severidad(double? Indice, IReadOnlyDictionary<string, long> PorMagnitud, double? PorcentajeValidados);

public sealed record Atencion(double? PorcentajeResueltos, double? TiempoPromedioResolucionMinutos);

/// <summary>Indicadores de un grupo ya anonimizados: si el grupo es pequeño, todo va en null y Suprimido = true.</summary>
public sealed record ConjuntoIndicadores(long? Reportes, Duracion? Duracion, Severidad? Severidad, Atencion? Atencion, bool Suprimido)
{
    public static ConjuntoIndicadores Calcular(Agregado? a)
    {
        if (a is null || !PoliticaAnonimizacion.Publicable(a))
            return new ConjuntoIndicadores(null, null, null, null, Suprimido: a is not null && a.Reportes > 0);
        var n = (double)a.Reportes;
        var duracion = PoliticaAnonimizacion.DuracionPublicable(a)
            ? new Duracion(Redondear(a.DuracionPromedio), Redondear(a.DuracionMediana), Redondear(a.DuracionP90), a.ConDuracion)
            : new Duracion(null, null, null, a.ConDuracion);
        var indice = (a.Vivienda * 1 + a.Cuadra * 2 + a.Barrio * 3 + a.Sector * 4) / n;
        return new ConjuntoIndicadores(a.Reportes, duracion,
            new Severidad(Math.Round(indice, 2),
                new Dictionary<string, long>
                {
                    ["VIVIENDA"] = a.Vivienda, ["CUADRA"] = a.Cuadra, ["BARRIO"] = a.Barrio, ["SECTOR"] = a.Sector,
                },
                Porcentaje(a.Validados, n)),
            new Atencion(Porcentaje(a.Resueltos, n), Redondear(a.TiempoResolucionPromedio)),
            Suprimido: false);
    }

    private static double Porcentaje(long parte, double total) => Math.Round(100 * parte / total, 1);

    private static double? Redondear(double? v) => v is null ? null : Math.Round(v.Value, 1);
}
