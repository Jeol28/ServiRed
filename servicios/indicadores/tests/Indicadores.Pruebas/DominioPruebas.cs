using System.Text.Json;
using ServiRed.Indicadores.Aplicacion;
using ServiRed.Indicadores.Dominio;

namespace ServiRed.Indicadores.Pruebas;

public class DominioPruebas
{
    private static readonly DateTimeOffset Ahora = new(2026, 11, 17, 12, 0, 0, TimeSpan.Zero);

    [Fact]
    public void ElPeriodoPorDefectoSonLosUltimos30Dias()
    {
        var p = Periodo.Crear(null, null, Ahora);

        Assert.Equal(Ahora, p.Hasta);
        Assert.Equal(TimeSpan.FromDays(30), p.Hasta - p.Desde);
    }

    [Fact]
    public void RechazaPeriodosInvertidosOMayoresAUnAnio()
    {
        Assert.Throws<DatosInvalidos>(() => Periodo.Crear(Ahora, Ahora.AddDays(-1), Ahora));
        Assert.Throws<DatosInvalidos>(() => Periodo.Crear(Ahora.AddDays(-400), Ahora, Ahora));
    }

    [Fact]
    public void SuprimeLosGruposConMenosDeTresReportes()
    {
        var pequeno = ConjuntoIndicadores.Calcular(VistaFalsa.Grupo(2, 2));

        Assert.True(pequeno.Suprimido);
        Assert.Null(pequeno.Reportes);
        Assert.Null(pequeno.Duracion);
        Assert.False(ConjuntoIndicadores.Calcular(null).Suprimido);
    }

    [Fact]
    public void PublicaLosGruposGrandesYSoloLaDuracionConSuficientesDatos()
    {
        var conDuracion = ConjuntoIndicadores.Calcular(VistaFalsa.Grupo(5, 3));
        Assert.Equal(5, conDuracion.Reportes);
        Assert.Equal(90, conDuracion.Duracion!.Mediana);

        var sinDuracion = ConjuntoIndicadores.Calcular(VistaFalsa.Grupo(5, 2));
        Assert.Null(sinDuracion.Duracion!.Mediana);
        Assert.Equal(2, sinDuracion.Duracion.ReportesConDuracion);
    }

    [Fact]
    public void ElIndiceDeSeveridadEsElPromedioDeLaMagnitud()
    {
        var a = new Agregado(null, null, null, null, null, null, null, 4, 0, null, null, null, 1, 1, 1, 1, 2, 1, null);

        var i = ConjuntoIndicadores.Calcular(a);

        Assert.Equal(2.5, i.Severidad!.Indice);
        Assert.Equal(50, i.Severidad.PorcentajeValidados);
        Assert.Equal(25, i.Atencion!.PorcentajeResueltos);
    }

    [Fact]
    public void LasCoordenadasRegulatoriasSeRedondeanACienMetros() =>
        Assert.Equal(4.629, PoliticaAnonimizacion.RedondearRegulatorio(4.628734));

    [Fact]
    public void CadaIndicadorTieneDefinicionYFuente() =>
        Assert.All(CatalogoIndicadores.Todos, d =>
        {
            Assert.False(string.IsNullOrWhiteSpace(d.Definicion));
            Assert.False(string.IsNullOrWhiteSpace(d.Fuente));
        });

    private static JsonElement Json(string s) => JsonDocument.Parse(s).RootElement;

    [Fact]
    public void TraduceCadaEventoALaVistaSinDatosDelCiudadano()
    {
        var id = Guid.NewGuid();
        var creado = ProyeccionEventos.Traducir("reporte.creado", Ahora, Json($$"""
            {"idReporte":"{{id}}","tipoServicio":"GAS","ubicacion":{"latitud":4.6,"longitud":-74.1},
             "codigoMunicipio":"11001","inicioEstimado":"2026-11-17T10:00:00Z","magnitud":"BARRIO",
             "fechaRegistro":"2026-11-17T11:00:00Z","descripcion":"texto del ciudadano"}
            """))!;
        Assert.Equal("GAS", creado.TipoServicio);
        Assert.Equal(4.6, creado.Latitud);
        Assert.DoesNotContain("texto del ciudadano", creado.ToString());

        var rest = ProyeccionEventos.Traducir("restablecimiento.registrado", Ahora, Json($$"""
            {"idReporte":"{{id}}","fechaRestablecimiento":"2026-11-17T12:00:00Z","duracionMinutos":120}
            """))!;
        Assert.Equal(120, rest.DuracionMinutos);

        var caso = ProyeccionEventos.Traducir("caso.actualizado", Ahora, Json($$"""
            {"idCaso":"{{Guid.NewGuid()}}","idReporte":"{{id}}","estado":"RESUELTO","prioridad":"ALTA",
             "idEmpresaOperadora":"a0000000-0000-4000-8000-000000000001","fechaActualizacion":"2026-11-17T12:30:00Z"}
            """))!;
        Assert.Equal("RESUELTO", caso.EstadoCaso);
        Assert.NotNull(caso.ResueltoEn);
        Assert.NotNull(caso.IdEmpresa);
    }

    [Fact]
    public void IgnoraLaValidacionDelRestablecimientoYRechazaDatosRotos()
    {
        Assert.Null(ProyeccionEventos.Traducir("validacion.registrada", Ahora, Json($$"""
            {"idReporte":"{{Guid.NewGuid()}}","objeto":"RESTABLECIMIENTO","sentido":"CONFIRMA","confirmacionesNetas":1,"validado":false}
            """)));
        Assert.Throws<FallaPermanente>(() => ProyeccionEventos.Traducir("reporte.creado", Ahora, Json("{\"idReporte\":\"x\"}")));
        Assert.Throws<FallaPermanente>(() => ProyeccionEventos.Traducir("reporte.creado", Ahora, Json("{}")));
    }
}
