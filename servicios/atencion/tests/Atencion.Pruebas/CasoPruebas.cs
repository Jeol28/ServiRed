using ServiRed.Atencion.Dominio;

namespace ServiRed.Atencion.Pruebas;

public class CasoPruebas
{
    private static readonly DateTimeOffset Ahora = new(2026, 11, 17, 15, 0, 0, TimeSpan.Zero);
    private static readonly Guid Empresa = Guid.NewGuid();
    private readonly Actor _operador = new(Guid.NewGuid(), Rol.OPERADOR, Empresa);
    private readonly Actor _otroOperador = new(Guid.NewGuid(), Rol.OPERADOR, Guid.NewGuid());
    private readonly Actor _regulador = new(Guid.NewGuid(), Rol.REGULADOR, null);
    private readonly Actor _admin = new(Guid.NewGuid(), Rol.ADMINISTRADOR, null);

    private static Caso Caso(EstadoCaso estado = EstadoCaso.RECIBIDO) =>
        Dominio.Caso.AbrirDesdeReporte(Guid.NewGuid(), TipoServicio.ENERGIA, "11001", Magnitud.CUADRA, Ahora, Empresa)
        with { Estado = estado };

    [Fact]
    public void UnCasoNuevoQuedaRecibidoYPriorizado()
    {
        var caso = Dominio.Caso.AbrirDesdeReporte(Guid.NewGuid(), TipoServicio.GAS, "11001", Magnitud.SECTOR, Ahora, Empresa);

        Assert.Equal(EstadoCaso.RECIBIDO, caso.Estado);
        Assert.Equal(Prioridad.CRITICA, caso.Prioridad);
        Assert.Equal(0, caso.Version);
    }

    [Theory]
    [InlineData("1100")]
    [InlineData("ABCDE")]
    [InlineData("")]
    public void RechazaCodigosDivipolaInvalidos(string codigo) =>
        Assert.Throws<ArgumentException>(() =>
            Dominio.Caso.AbrirDesdeReporte(Guid.NewGuid(), TipoServicio.GAS, codigo, Magnitud.BARRIO, Ahora));

    [Fact]
    public void ElCicloCompletoRecibidoAtencionResueltoCerrado()
    {
        var atender = Caso().Atender(_operador, Ahora);
        Assert.Equal(EstadoCaso.EN_ATENCION, atender.Estado);
        Assert.Equal(_operador.IdCuenta, atender.AsignadoA);

        var resolver = Caso(EstadoCaso.EN_ATENCION).Resolver(_operador, "Cambio de transformador", Ahora);
        Assert.Equal(EstadoCaso.RESUELTO, resolver.Estado);
        Assert.Equal(Ahora, resolver.ResueltoEn);

        var cerrar = Caso(EstadoCaso.RESUELTO).Cerrar(_operador, null, Ahora);
        Assert.Equal(EstadoCaso.CERRADO, cerrar.Estado);
    }

    [Fact]
    public void LasTransicionesInvalidasSeRechazan()
    {
        Assert.Equal("TRANSICION", Assert.Throws<ReglaIncumplida>(() => Caso(EstadoCaso.EN_ATENCION).Atender(_operador, Ahora)).Motivo);
        Assert.Throws<ReglaIncumplida>(() => Caso(EstadoCaso.CERRADO).Resolver(_operador, "x", Ahora));
        Assert.Throws<ReglaIncumplida>(() => Caso(EstadoCaso.CERRADO).Cerrar(_operador, "x", Ahora));
        Assert.Throws<DatosInvalidos>(() => Caso(EstadoCaso.EN_ATENCION).Resolver(_operador, "  ", Ahora));
    }

    [Fact]
    public void CerrarSinResolverExigeMotivo()
    {
        Assert.Throws<DatosInvalidos>(() => Caso().Cerrar(_operador, null, Ahora));
        Assert.Equal("Reporte duplicado", Caso().Cerrar(_operador, "Reporte duplicado", Ahora).Resolucion);
    }

    [Fact]
    public void ElOperadorSoloGestionaCasosDeSuEmpresaYElReguladorSoloConsulta()
    {
        Assert.Throws<AccesoDenegado>(() => Caso().Atender(_otroOperador, Ahora));
        Assert.Throws<AccesoDenegado>(() => Caso().Atender(_regulador, Ahora));
        Assert.True(_regulador.PuedeVer(Empresa));
        Assert.False(_otroOperador.PuedeVer(Empresa));
        Assert.Null(Caso().Atender(_admin, Ahora).AsignadoA);
    }

    [Fact]
    public void SoloElAdministradorEnrutaYSoloALaDuenaDeLaZona()
    {
        var duena = Guid.NewGuid();
        var zona = new Cobertura("11001-L11", "Suba", duena, "Área Limpia");
        Assert.Throws<AccesoDenegado>(() => Caso().Enrutar(_operador, duena, zona, Ahora));
        Assert.Equal(duena, Caso().Enrutar(_admin, duena, zona, Ahora).IdEmpresa);

        var otra = Assert.Throws<ReglaIncumplida>(() => Caso().Enrutar(_admin, Guid.NewGuid(), zona, Ahora));
        Assert.Equal("FUERA_DE_ZONA", otra.Motivo);
        var sinDuena = Assert.Throws<ReglaIncumplida>(() =>
            Caso().Enrutar(_admin, duena, zona with { IdEmpresa = null }, Ahora));
        Assert.Equal("SIN_EMPRESA_EN_ZONA", sinDuena.Motivo);
    }

    [Fact]
    public void ElRestablecimientoDelCiudadanoResuelveElCasoAbierto()
    {
        Assert.Equal(EstadoCaso.RESUELTO, Caso(EstadoCaso.EN_ATENCION).ResolverPorRestablecimiento(90, Ahora)!.Estado);
        Assert.Null(Caso(EstadoCaso.RESUELTO).ResolverPorRestablecimiento(90, Ahora));
        Assert.Null(Caso(EstadoCaso.CERRADO).ResolverPorRestablecimiento(90, Ahora));
    }

    [Fact]
    public void LaValidacionRecalculaLaPrioridadSalvoQueSeaManual()
    {
        var cambio = Caso().RegistrarValidacionDelCorte(true, Ahora)!;
        Assert.True(cambio.CorteValidado);
        Assert.Equal(Prioridad.MEDIA, cambio.Prioridad ?? Prioridad.MEDIA);

        var barrio = Dominio.Caso.AbrirDesdeReporte(Guid.NewGuid(), TipoServicio.ACUEDUCTO, "11001", Magnitud.BARRIO, Ahora, Empresa);
        Assert.Equal(Prioridad.ALTA, barrio.RegistrarValidacionDelCorte(true, Ahora)!.Prioridad);

        var manual = barrio with { PrioridadManual = true, Prioridad = Prioridad.BAJA };
        Assert.Null(manual.RegistrarValidacionDelCorte(true, Ahora)!.Prioridad);
        Assert.Null((barrio with { CorteValidado = true }).RegistrarValidacionDelCorte(true, Ahora));
    }

    [Theory]
    [InlineData(TipoServicio.ENERGIA, Magnitud.VIVIENDA, false, Prioridad.BAJA)]
    [InlineData(TipoServicio.ENERGIA, Magnitud.CUADRA, false, Prioridad.MEDIA)]
    [InlineData(TipoServicio.ACUEDUCTO, Magnitud.BARRIO, false, Prioridad.MEDIA)]
    [InlineData(TipoServicio.ACUEDUCTO, Magnitud.SECTOR, false, Prioridad.ALTA)]
    [InlineData(TipoServicio.ACUEDUCTO, Magnitud.SECTOR, true, Prioridad.CRITICA)]
    [InlineData(TipoServicio.GAS, Magnitud.BARRIO, false, Prioridad.ALTA)]
    public void PoliticaDePrioridad(TipoServicio servicio, Magnitud magnitud, bool validado, Prioridad esperada) =>
        Assert.Equal(esperada, PoliticaPrioridad.Calcular(servicio, magnitud, validado));


    [Fact]
    public void LaModeracionSeDecideUnaVezYConNota()
    {
        var m = new Moderacion(Guid.NewGuid(), TipoServicio.ENERGIA, "11001", null, 4.6, -74.1, "Conexión directa", Ahora,
            Empresa, EstadoModeracion.PENDIENTE, null, null, null, 0, []);

        var d = m.Decidir(_operador, EstadoModeracion.VALIDADA, "Verificado en terreno", Ahora);
        Assert.Equal(EstadoModeracion.VALIDADA, d.Estado);
        Assert.Throws<DatosInvalidos>(() => m.Decidir(_operador, EstadoModeracion.DESCARTADA, "no", Ahora));
        Assert.Throws<AccesoDenegado>(() => m.Decidir(_otroOperador, EstadoModeracion.VALIDADA, "Verificado", Ahora));
        Assert.Throws<ReglaIncumplida>(() =>
            (m with { Estado = EstadoModeracion.VALIDADA }).Decidir(_operador, EstadoModeracion.DESCARTADA, "Revisado otra vez", Ahora));
    }
}
