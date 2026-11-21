using ServiRed.Atencion.Aplicacion;
using ServiRed.Atencion.Aplicacion.Eventos;
using ServiRed.Atencion.Aplicacion.Puertos;
using ServiRed.Atencion.Dominio;

namespace ServiRed.Atencion.Pruebas;

public class AplicacionPruebas
{
    private readonly PersistenciaFalsa _p = new();
    private readonly ReaccionesAEventos _reacciones;
    private readonly GestionCasos _gestion;
    private readonly IdCorrelacion _c = IdCorrelacion.Nuevo();
    private readonly Actor _operador = new(Guid.NewGuid(), Rol.OPERADOR, PersistenciaFalsa.EmpresaA);

    public AplicacionPruebas()
    {
        _reacciones = new ReaccionesAEventos(_p, _p, _p, TimeProvider.System);
        _gestion = new GestionCasos(_p, _p, TimeProvider.System);
    }

    private static DatosReporteCreado Reporte(string municipio = "11001") =>
        new(Guid.NewGuid(), "ENERGIA", municipio, "SECTOR", DateTimeOffset.UtcNow, new UbicacionEvento(4.6, -74.1),
            "Sin luz en el sector");

    [Fact]
    public async Task ReporteCreadoAbreElCasoEnrutadoYPriorizado()
    {
        var caso = await _reacciones.AbrirCasoAsync(Reporte(), _c, CancellationToken.None);

        Assert.Equal(PersistenciaFalsa.EmpresaA, caso.IdEmpresa);
        Assert.Equal(Prioridad.ALTA, caso.Prioridad);
        Assert.Equal("Sin luz en el sector", caso.Descripcion);
        Assert.Contains(_c.Valor, _p.Correlaciones);
    }

    [Fact]
    public async Task AbrirElCasoPublicaSuEstadoPrioridadYEmpresa()
    {
        var caso = await _reacciones.AbrirCasoAsync(Reporte(), _c, CancellationToken.None);

        var e = Assert.Single(_p.Eventos);
        Assert.Equal("caso.actualizado", e.Tipo);
        Assert.Equal(Guid.Parse(_c.Valor), e.CorrelationId);
        Assert.Equal(caso.AbiertoEn, e.FechaOcurrencia);
        Assert.Equal("RECIBIDO", e.Datos["estado"]);
        Assert.Equal("ALTA", e.Datos["prioridad"]);
        Assert.Equal(PersistenciaFalsa.EmpresaA.ToString(), e.Datos["idEmpresaOperadora"]);
        Assert.False(e.Datos.ContainsKey("estadoAnterior"));
        Assert.False(e.Datos.ContainsKey("idActor"));

        await _reacciones.AbrirCasoAsync(Reporte("91001"), _c, CancellationToken.None);
        Assert.Equal(2, _p.Eventos.Count);
        Assert.False(_p.Eventos.ToArray()[1].Datos.ContainsKey("idEmpresaOperadora"), "sin empresa se omite (no null)");
    }

    [Fact]
    public async Task SinCoberturaElCasoQuedaSinEmpresa()
    {
        var caso = await _reacciones.AbrirCasoAsync(Reporte("91001"), _c, CancellationToken.None);

        Assert.Null(caso.IdEmpresa);
    }

    [Fact]
    public async Task UnaRedeliveryNoDuplicaElCaso()
    {
        var datos = Reporte();
        var a = await _reacciones.AbrirCasoAsync(datos, _c, CancellationToken.None);
        var b = await _reacciones.AbrirCasoAsync(datos, _c, CancellationToken.None);

        Assert.Equal(a.IdCaso, b.IdCaso);
        Assert.Single(_p.Casos);
        Assert.Single(_p.Eventos);
    }

    [Fact]
    public async Task ElRestablecimientoResuelveElCasoYPublicaCasoActualizado()
    {
        var caso = await _reacciones.AbrirCasoAsync(Reporte(), _c, CancellationToken.None);
        _p.Eventos.Clear();   // la apertura

        var resuelto = await _reacciones.RegistrarRestablecimientoAsync(
            new DatosRestablecimiento(caso.IdReporte, DateTimeOffset.UtcNow, 95), IdCorrelacion.Nuevo(), CancellationToken.None);

        Assert.Equal(EstadoCaso.RESUELTO, resuelto!.Estado);
        var evento = Assert.Single(_p.Eventos);
        Assert.Equal("caso.actualizado", evento.Tipo);
        Assert.Equal("RESUELTO", evento.Datos["estado"]);
        Assert.Equal("RECIBIDO", evento.Datos["estadoAnterior"]);
    }

    [Fact]
    public async Task ElEventoQueLlegaAntesQueElCasoSeReintenta() =>
        await Assert.ThrowsAsync<InvalidOperationException>(() => _reacciones.RegistrarRestablecimientoAsync(
            new DatosRestablecimiento(Guid.NewGuid(), DateTimeOffset.UtcNow, 10), _c, CancellationToken.None));

    [Fact]
    public async Task LaValidacionDelCorteSubeLaPrioridadReintentandoAnteConcurrencia()
    {
        var caso = await _reacciones.AbrirCasoAsync(Reporte() with { TipoServicio = "ACUEDUCTO", Magnitud = "BARRIO",
            CodigoMunicipio = "05001" }, _c, CancellationToken.None);
        _p.Eventos.Clear();   // la apertura
        _p.ConflictosPendientes = 1;

        var actualizado = await _reacciones.RegistrarValidacionAsync(
            new DatosValidacion(caso.IdReporte, "CORTE", "CONFIRMA", 3, true), IdCorrelacion.Nuevo(), CancellationToken.None);

        Assert.True(actualizado!.CorteValidado);
        Assert.Equal(Prioridad.ALTA, actualizado.Prioridad);
        Assert.Equal("ALTA", Assert.Single(_p.Eventos).Datos["prioridad"]);
    }

    [Fact]
    public async Task LaDenunciaAbreSuModeracionEnLaEmpresaConCobertura()
    {
        var m = await _reacciones.AbrirModeracionAsync(
            new DatosDenuncia(Guid.NewGuid(), "ENERGIA", "11001", DateTimeOffset.UtcNow), _c, CancellationToken.None);

        Assert.Equal(PersistenciaFalsa.EmpresaA, m.IdEmpresa);
        Assert.Equal(EstadoModeracion.PENDIENTE, m.Estado);
    }

    [Fact]
    public async Task ElOperadorSoloVeSuEmpresaYNoSeLeRevelaLaExistenciaDeOtros()
    {
        var propio = _p.Sembrar(PersistenciaFalsa.EmpresaA);
        var ajeno = _p.Sembrar(PersistenciaFalsa.EmpresaB);

        var bandeja = await _gestion.ListarAsync(_operador, new FiltroCasos(IdEmpresa: PersistenciaFalsa.EmpresaB), _c,
            CancellationToken.None);

        Assert.Equal(propio.IdCaso, Assert.Single(bandeja.Elementos).IdCaso);
        await Assert.ThrowsAsync<NoEncontrado>(() => _gestion.ConsultarAsync(_operador, ajeno.IdCaso, _c, CancellationToken.None));
    }

    [Fact]
    public async Task CerrarPublicaCasoActualizadoYCasoCerrado()
    {
        var caso = _p.Sembrar(estado: EstadoCaso.RESUELTO);

        await _gestion.CerrarAsync(_operador, caso.IdCaso, null, _c, CancellationToken.None);

        Assert.Equal(["caso.actualizado", "caso.cerrado"], _p.Eventos.Select(e => e.Tipo));
        Assert.All(_p.Eventos, e => Assert.Equal(_c.Valor, e.CorrelationId.ToString()));
    }

    [Fact]
    public async Task SoloSeAsignaAOperadoresDeLaEmpresaDelCaso()
    {
        var caso = _p.Sembrar();
        var colega = Guid.NewGuid();
        _p.Operadores.Add((colega, PersistenciaFalsa.EmpresaA));

        Assert.Equal(colega, (await _gestion.AsignarAsync(_operador, caso.IdCaso, colega, _c, CancellationToken.None)).AsignadoA);
        var e = await Assert.ThrowsAsync<ReglaIncumplida>(() =>
            _gestion.AsignarAsync(_operador, caso.IdCaso, Guid.NewGuid(), IdCorrelacion.Nuevo(), CancellationToken.None));
        Assert.Equal("OPERADOR_INVALIDO", e.Motivo);
    }


    [Fact]
    public async Task LosEventosDeCasoLlevanAlActor()
    {
        var caso = _p.Sembrar();

        await _gestion.AtenderAsync(_operador, caso.IdCaso, _c, CancellationToken.None);

        Assert.Equal(_operador.IdCuenta.ToString(), Assert.Single(_p.Eventos).Datos["idActor"]);
    }
}
