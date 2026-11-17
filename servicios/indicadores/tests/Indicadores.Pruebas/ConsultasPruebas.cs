using ServiRed.Indicadores.Aplicacion;
using ServiRed.Indicadores.Dominio;

namespace ServiRed.Indicadores.Pruebas;

public class ConsultasPruebas
{
    private readonly VistaFalsa _vista = new();
    private readonly ConsultasIndicadores _consultas;
    private readonly FiltroVista _filtro = new(Periodo.Crear(null, null, DateTimeOffset.UtcNow));
    private readonly IdCorrelacion _c = IdCorrelacion.Nuevo();
    private static readonly Guid Empresa = Guid.NewGuid();

    public ConsultasPruebas() => _consultas = new ConsultasIndicadores(_vista);

    [Fact]
    public async Task ElMapaPublicoNoMuestraCeldasPequenas()
    {
        _vista.Agregados[Agrupacion.CELDA] =
        [
            VistaFalsa.Grupo(5, codigo: null, lat: 4.635, lon: -74.065),
            VistaFalsa.Grupo(1, codigo: null, lat: 4.645, lon: -74.075),
        ];

        var mapa = await _consultas.MapaPublicoAsync(_filtro, _c, CancellationToken.None);

        var celda = Assert.Single(mapa.Celdas);
        Assert.Equal(5, celda.Reportes);
        Assert.Contains("3 reportes", mapa.Metadatos.Anonimizacion);
    }

    [Fact]
    public async Task LosDatosAbiertosMarcanLasFilasSuprimidas()
    {
        _vista.Agregados[Agrupacion.MUNICIPIO_SERVICIO_MES] =
            [VistaFalsa.Grupo(10, 5, servicio: "ENERGIA"), VistaFalsa.Grupo(2, 2, codigo: "05001", servicio: "GAS")];

        var datos = await _consultas.DatosAbiertosAsync(_filtro, _c, CancellationToken.None);

        Assert.Equal(2, datos.Filas.Count);
        var suprimida = datos.Filas.Single(f => f.CodigoMunicipio == "05001");
        Assert.True(suprimida.Suprimido);
        Assert.Null(suprimida.Reportes);
        Assert.Equal("2026-11", suprimida.Mes);
        Assert.Equal(10, datos.Filas.Single(f => f.CodigoMunicipio == "11001").Reportes);
    }

    [Fact]
    public async Task ElOperadorSoloVeEnElMapaLosReportesDeSuEmpresa()
    {
        _vista.Hechos = [VistaFalsa.Hecho(Empresa), VistaFalsa.Hecho(Guid.NewGuid())];

        var puntos = await _consultas.MapaOperativoAsync(new Actor(Guid.NewGuid(), Rol.OPERADOR, Empresa),
            _filtro with { IdEmpresa = Guid.NewGuid() }, _c, CancellationToken.None);

        Assert.Equal(Empresa, Assert.Single(puntos).IdEmpresa);
        await Assert.ThrowsAsync<AccesoDenegado>(() =>
            _consultas.MapaOperativoAsync(new Actor(Guid.NewGuid(), Rol.CIUDADANO, null), _filtro, _c, CancellationToken.None));
    }

    [Fact]
    public async Task LaExportacionEsSoloRegulatoriaRecorreTodosLosLotesYRedondea()
    {
        _vista.Hechos = Enumerable.Range(0, ConsultasIndicadores.LoteExportacion + 3).Select(_ => VistaFalsa.Hecho()).ToList();
        var regulador = new Actor(Guid.NewGuid(), Rol.REGULADOR, null);

        var filas = new List<Hecho>();
        await foreach (var h in _consultas.ExportarAsync(regulador, _filtro, _c, CancellationToken.None)) filas.Add(h);

        Assert.Equal(ConsultasIndicadores.LoteExportacion + 3, filas.Count);
        Assert.All(filas, h => Assert.Equal(4.629, h.Latitud));
        await Assert.ThrowsAsync<AccesoDenegado>(async () =>
        {
            await foreach (var _ in _consultas.ExportarAsync(new Actor(Guid.NewGuid(), Rol.OPERADOR, Empresa), _filtro, _c,
                               CancellationToken.None)) { }
        });
    }

    [Fact]
    public async Task ElResumenIncluyeMetadatosDeCoberturaYDefiniciones()
    {
        _vista.Agregados[Agrupacion.NINGUNA] = [VistaFalsa.Grupo(8, 4)];
        _vista.Agregados[Agrupacion.SERVICIO] = [VistaFalsa.Grupo(8, 4, servicio: "ENERGIA")];

        var r = await _consultas.ResumenAsync(_filtro, _c, CancellationToken.None);

        Assert.Equal(8, r.Indicadores.Reportes);
        Assert.Equal("ENERGIA", Assert.Single(r.PorServicio).TipoServicio);
        Assert.Equal(10, r.Metadatos.Cobertura.ReportesEnVista);
        Assert.Equal(5, r.Metadatos.Indicadores.Count);
        Assert.NotNull(r.Metadatos.ActualizadoEn);
    }
}
