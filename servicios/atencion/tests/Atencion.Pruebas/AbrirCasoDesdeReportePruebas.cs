using ServiRed.Atencion.Aplicacion;
using ServiRed.Atencion.Aplicacion.Eventos;
using ServiRed.Atencion.Dominio;

namespace ServiRed.Atencion.Pruebas;

public class AbrirCasoDesdeReportePruebas
{
    private readonly RepositorioCasosFalso _repositorio = new();
    private readonly AbrirCasoDesdeReporte _casoDeUso;

    public AbrirCasoDesdeReportePruebas() => _casoDeUso = new AbrirCasoDesdeReporte(_repositorio, TimeProvider.System);

    private static DatosReporteCreado Datos(string servicio = "ACUEDUCTO", string municipio = "05001") =>
        new(Guid.NewGuid(), servicio, municipio, "BARRIO", DateTimeOffset.UtcNow);

    [Fact]
    public async Task AbreElCasoConLosDatosDelReporte()
    {
        var datos = Datos();
        var correlacion = IdCorrelacion.Nuevo();

        var caso = await _casoDeUso.ManejarAsync(datos, correlacion, CancellationToken.None);

        Assert.Equal(datos.IdReporte, caso.IdReporte);
        Assert.Equal(TipoServicio.ACUEDUCTO, caso.TipoServicio);
        Assert.Equal("05001", caso.CodigoMunicipio);
        Assert.Equal(correlacion.Valor, Assert.Single(_repositorio.Correlaciones));
    }

    [Fact]
    public async Task UnaRedeliveryDevuelveElMismoCaso()
    {
        var datos = Datos();

        var primero = await _casoDeUso.ManejarAsync(datos, IdCorrelacion.Nuevo(), CancellationToken.None);
        var segundo = await _casoDeUso.ManejarAsync(datos, IdCorrelacion.Nuevo(), CancellationToken.None);

        Assert.Equal(primero.IdCaso, segundo.IdCaso);
        Assert.Single(_repositorio.Casos);
    }

    [Fact]
    public async Task DatosInvalidosSonUnaFallaPermanente()
    {
        await Assert.ThrowsAsync<FallaPermanente>(() =>
            _casoDeUso.ManejarAsync(Datos(servicio: "TELEFONIA"), IdCorrelacion.Nuevo(), CancellationToken.None));
        await Assert.ThrowsAsync<FallaPermanente>(() =>
            _casoDeUso.ManejarAsync(Datos(municipio: "1"), IdCorrelacion.Nuevo(), CancellationToken.None));
        Assert.Equal(0, _repositorio.Llamadas);
    }
}
