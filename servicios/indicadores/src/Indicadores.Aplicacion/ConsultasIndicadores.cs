using ServiRed.Indicadores.Dominio;

namespace ServiRed.Indicadores.Aplicacion;

/// <summary>Metadatos de toda publicación (RNF-DAT4): definición, fuente, periodo, cobertura y actualización.</summary>
public sealed record Metadatos(
    IReadOnlyList<DefinicionIndicador> Indicadores,
    string Fuente,
    Periodo Periodo,
    object Filtros,
    Cobertura Cobertura,
    DateTimeOffset? ActualizadoEn,
    string Anonimizacion);

public sealed record Cobertura(long ReportesEnVista, long MunicipiosConReportes, DateTimeOffset? PrimerReporte,
    DateTimeOffset? UltimoReporte);

public sealed record Resumen(Metadatos Metadatos, ConjuntoIndicadores Indicadores, IReadOnlyList<IndicadorPorServicio> PorServicio);

public sealed record IndicadorPorServicio(string TipoServicio, ConjuntoIndicadores Indicadores);

public sealed record IndicadorTerritorial(string Codigo, string Nombre, string? Departamento, ConjuntoIndicadores Indicadores);

public sealed record Territorial(Metadatos Metadatos, NivelTerritorial Nivel, IReadOnlyList<IndicadorTerritorial> Territorios);

/// <param name="CeldaGrados">lado de la celda: 0,01° en una cabecera municipal, 0,05° fuera (ADR-28)</param>
public sealed record CeldaMapa(double Latitud, double Longitud, long Reportes, double CeldaGrados);

public sealed record MapaPublico(Metadatos Metadatos, double CeldaGrados, Situacion? Situacion, IReadOnlyList<CeldaMapa> Celdas);

public sealed record FilaDatoAbierto(string CodigoMunicipio, string Municipio, string Departamento, string TipoServicio,
    string Mes, long? Reportes, double? DuracionMedianaMinutos, double? IndiceSeveridad, double? PorcentajeResueltos,
    bool Suprimido);

public sealed record DatosAbiertos(Metadatos Metadatos, IReadOnlyList<FilaDatoAbierto> Filas);

public sealed record PuntoOperativo(Guid IdReporte, string TipoServicio, double Latitud, double Longitud, string? Magnitud,
    string? EstadoCaso, string? Prioridad, bool Validado, DateTimeOffset FechaRegistro, Guid? IdEmpresa);

/// <summary>
/// Calculadora de indicadores, generador de mapas y exportación (RF-8 a RF-10).
/// Todo lo público pasa por <see cref="PoliticaAnonimizacion"/>; lo detallado solo
/// lo ve el personal autorizado.
/// </summary>
public sealed class ConsultasIndicadores(IVistaLectura vista)
{
    public const int LimiteMapaOperativo = 5000;
    public const int LoteExportacion = 5000;

    public async Task<Resumen> ResumenAsync(FiltroVista f, IdCorrelacion c, CancellationToken ct)
    {
        var total = (await vista.AgregarAsync(f, Agrupacion.NINGUNA, 0, 0, c, ct)).FirstOrDefault();
        var porServicio = await vista.AgregarAsync(f, Agrupacion.SERVICIO, 0, 0, c, ct);
        return new Resumen(await MetadatosAsync(f, c, ct), ConjuntoIndicadores.Calcular(total),
            porServicio.Select(a => new IndicadorPorServicio(a.TipoServicio!, ConjuntoIndicadores.Calcular(a))).ToList());
    }

    public async Task<Territorial> TerritorialAsync(FiltroVista f, NivelTerritorial nivel, IdCorrelacion c,
        CancellationToken ct)
    {
        var agrupacion = nivel == NivelTerritorial.DEPARTAMENTO ? Agrupacion.DEPARTAMENTO : Agrupacion.MUNICIPIO;
        var grupos = await vista.AgregarAsync(f, agrupacion, 0, 0, c, ct);
        return new Territorial(await MetadatosAsync(f, c, ct), nivel,
            grupos.Select(a => new IndicadorTerritorial(a.Codigo!, a.Nombre!,
                    nivel == NivelTerritorial.MUNICIPIO ? a.Departamento : null, ConjuntoIndicadores.Calcular(a)))
                .ToList());
    }

    /// <summary>
    /// RF-8 público: celdas de ~1,1 km en las cabeceras municipales (se muestran desde 3 reportes)
    /// y de ~5,5 km fuera de ellas (desde 5), ADR-28. Si una celda rural y una urbana comparten
    /// centro, se publican como una sola, cada una ya filtrada con su umbral.
    /// </summary>
    public async Task<MapaPublico> MapaPublicoAsync(FiltroVista f, IdCorrelacion c, CancellationToken ct)
    {
        var celdas = await vista.AgregarAsync(f, Agrupacion.CELDA, PoliticaAnonimizacion.CeldaGrados,
            PoliticaAnonimizacion.CeldaRuralGrados, c, ct);
        return new MapaPublico(await MetadatosAsync(f, c, ct), PoliticaAnonimizacion.CeldaGrados, f.Situacion,
            celdas.Where(PoliticaAnonimizacion.CeldaPublicable)
                .GroupBy(a => (Lat: Math.Round(a.CeldaLatitud!.Value, 4), Lon: Math.Round(a.CeldaLongitud!.Value, 4)))
                .Select(g => new CeldaMapa(g.Key.Lat, g.Key.Lon, g.Sum(a => a.Reportes),
                    g.Max(PoliticaAnonimizacion.LadoDeCelda)))
                .ToList());
    }

    /// <summary>RF-10: conjunto abierto municipio × servicio × mes, solo agregados y con supresión.</summary>
    public async Task<DatosAbiertos> DatosAbiertosAsync(FiltroVista f, IdCorrelacion c, CancellationToken ct)
    {
        var grupos = await vista.AgregarAsync(f with { IdEmpresa = null }, Agrupacion.MUNICIPIO_SERVICIO_MES, 0, 0, c, ct);
        var filas = grupos.OrderBy(a => a.Mes).ThenBy(a => a.Codigo).ThenBy(a => a.TipoServicio)
            .Select(a =>
            {
                var i = ConjuntoIndicadores.Calcular(a);
                return new FilaDatoAbierto(a.Codigo!, a.Nombre!, a.Departamento!, a.TipoServicio!,
                    a.Mes!.Value.ToString("yyyy-MM"), i.Reportes, i.Duracion?.Mediana, i.Severidad?.Indice,
                    i.Atencion?.PorcentajeResueltos, i.Suprimido);
            }).ToList();
        return new DatosAbiertos(await MetadatosAsync(f, c, ct), filas);
    }

    /// <summary>RF-8 operativo: puntos exactos para el personal; el operador solo ve los de su empresa.</summary>
    public async Task<IReadOnlyList<PuntoOperativo>> MapaOperativoAsync(Actor actor, FiltroVista f, IdCorrelacion c,
        CancellationToken ct)
    {
        ExigirPersonal(actor, Rol.OPERADOR, Rol.REGULADOR, Rol.ADMINISTRADOR);
        var pagina = await vista.HechosAsync(f with { IdEmpresa = actor.EmpresaForzada ?? f.IdEmpresa }, 0,
            LimiteMapaOperativo, c, ct);
        return pagina.Elementos.Select(h => new PuntoOperativo(h.IdReporte, h.TipoServicio, h.Latitud, h.Longitud,
            h.Magnitud, h.EstadoCaso, h.Prioridad, h.Validado, h.FechaRegistro, h.IdEmpresa)).ToList();
    }

    /// <summary>
    /// RF-10 regulatorio: todos los reportes del periodo, sin datos del ciudadano y
    /// con coordenadas redondeadas (~110 m). Solo regulador y administrador.
    /// </summary>
    public async IAsyncEnumerable<Hecho> ExportarAsync(Actor actor, FiltroVista f, IdCorrelacion c,
        [System.Runtime.CompilerServices.EnumeratorCancellation] CancellationToken ct)
    {
        ExigirPersonal(actor, Rol.REGULADOR, Rol.ADMINISTRADOR);
        for (var desde = 0; ; desde += LoteExportacion)
        {
            var pagina = await vista.HechosAsync(f, desde, LoteExportacion, c, ct);
            foreach (var h in pagina.Elementos)
                yield return h with
                {
                    Latitud = PoliticaAnonimizacion.RedondearRegulatorio(h.Latitud),
                    Longitud = PoliticaAnonimizacion.RedondearRegulatorio(h.Longitud),
                };
            if (!pagina.HayMas) yield break;
        }
    }

    public async Task<Metadatos> MetadatosAsync(FiltroVista f, IdCorrelacion c, CancellationToken ct)
    {
        var estado = await vista.EstadoAsync(c, ct);
        return new Metadatos(CatalogoIndicadores.Todos, CatalogoIndicadores.Fuente, f.Periodo,
            new { tipoServicio = f.TipoServicio?.ToString(), f.CodigoDepartamento, f.CodigoMunicipio, situacion = f.Situacion?.ToString() },
            new Cobertura(estado.Reportes, estado.Municipios, estado.PrimerReporte, estado.UltimoReporte),
            estado.ActualizadoEn,
            $"Solo agregados; se suprimen los grupos con menos de {PoliticaAnonimizacion.UmbralMinimo} reportes; " +
            $"el mapa usa celdas de {PoliticaAnonimizacion.CeldaGrados}° (~1,1 km) en las cabeceras municipales y de " +
            $"{PoliticaAnonimizacion.CeldaRuralGrados}° (~5,5 km) fuera de ellas, donde se muestran desde " +
            $"{PoliticaAnonimizacion.UmbralRural} reportes.");
    }

    private static void ExigirPersonal(Actor actor, params Rol[] roles)
    {
        if (!roles.Contains(actor.Rol)) throw new AccesoDenegado("Su rol no tiene acceso a esta información");
    }
}
