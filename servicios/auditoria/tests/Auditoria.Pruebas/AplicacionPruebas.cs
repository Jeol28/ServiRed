using ServiRed.Auditoria.Aplicacion;
using ServiRed.Auditoria.Dominio;

namespace ServiRed.Auditoria.Pruebas;

public class AplicacionPruebas
{
    private readonly RepositorioFalso _repo = new();
    private readonly RegistroDeAuditoria _registro;
    private readonly IdCorrelacion _c = IdCorrelacion.Nuevo();

    public AplicacionPruebas() => _registro = new RegistroDeAuditoria(_repo, new DetectorAlertas(), _repo);

    [Fact]
    public async Task UnaRedeliveryNoDuplicaNiVuelveAContar()
    {
        var cuenta = Guid.NewGuid();
        var eventos = Enumerable.Range(0, 4)
            .Select(i => DominioPruebas.LoginFallido(cuenta, DateTimeOffset.UtcNow.AddSeconds(i))).ToList();
        foreach (var e in eventos) await _registro.RegistrarEventoAsync(e, _c, CancellationToken.None);
        // La cuarta llega dos veces: no debe completar el umbral de 5.
        await _registro.RegistrarEventoAsync(eventos[3], _c, CancellationToken.None);

        Assert.Equal(4, _repo.Registros.Count);
        Assert.Empty(_repo.Metricas);
    }

    [Fact]
    public async Task LaAlertaQuedaEnLaAuditoriaYEnLasMetricas()
    {
        var cuenta = Guid.NewGuid();
        for (var i = 0; i < 5; i++)
            await _registro.RegistrarEventoAsync(DominioPruebas.LoginFallido(cuenta, DateTimeOffset.UtcNow.AddSeconds(i)), _c,
                CancellationToken.None);

        var alerta = Assert.Single(_repo.Registros.Values, r => r.Categoria == Categorias.Alerta);
        Assert.Equal("FUERZA_BRUTA_CUENTA", alerta.Accion);
        Assert.Equal("ALTA", alerta.Detalle["severidad"]);
        Assert.Single(_repo.Metricas);
    }

    [Fact]
    public async Task LasAlertasDeAlertmanagerSeRegistranUnaVez()
    {
        var a = new AlertaExterna("ColaDeMensajesFallidos", "firing", "critica", "auditoria", "Hay mensajes en la DLQ",
            DateTimeOffset.UtcNow, "abc123");

        Assert.Equal(1, await _registro.RegistrarAlertasExternasAsync([a], _c, CancellationToken.None));
        Assert.Equal(0, await _registro.RegistrarAlertasExternasAsync([a], _c, CancellationToken.None));
        Assert.Equal(1, await _registro.RegistrarAlertasExternasAsync([a with { Estado = "resolved" }], _c, CancellationToken.None));
    }

    [Fact]
    public async Task SoloElPersonalAutorizadoConsultaYSoloElAdministradorVerifica()
    {
        var consultas = new ConsultasAuditoria(_repo);
        var regulador = new Actor(Guid.NewGuid(), Rol.REGULADOR, null);

        await consultas.ConsultarAsync(regulador, new FiltroAuditoria(), _c, CancellationToken.None);
        await Assert.ThrowsAsync<AccesoDenegado>(() =>
            consultas.ConsultarAsync(new Actor(Guid.NewGuid(), Rol.OPERADOR, Guid.NewGuid()), new FiltroAuditoria(), _c, CancellationToken.None));
        await Assert.ThrowsAsync<AccesoDenegado>(() => consultas.VerificarAsync(regulador, _c, CancellationToken.None));
        await Assert.ThrowsAsync<DatosInvalidos>(() =>
            consultas.ConsultarAsync(regulador, new FiltroAuditoria(Categoria: "X"), _c, CancellationToken.None));
    }
}
