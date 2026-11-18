using System.Text.Json;
using ServiRed.Indicadores.Aplicacion;
using ServiRed.Indicadores.Dominio;
using ServiRed.Indicadores.Infraestructura.Persistencia;

namespace ServiRed.Indicadores.Pruebas;

/// <summary>
/// Indicadores contra el contrato de eventos (contratos/eventos): como productor, el
/// accion.auditada de la exportación regulatoria cumple su esquema; como consumidor, la
/// proyección lee los ejemplos del contrato de Reportes (Java) y Atención (.NET), también
/// los mínimos sin campos opcionales.
/// </summary>
public class ContratoEventosPruebas
{
    public static TheoryData<string, Dictionary<string, object?>> Exportaciones() => new()
    {
        { "con filtros", new() { ["desde"] = "2026-10-01T00:00:00.0000000-05:00", ["hasta"] = "2026-11-18T23:59:59.0000000-05:00",
            ["tipoServicio"] = "ENERGIA", ["codigoDepartamento"] = "05", ["codigoMunicipio"] = "05001" } },
        { "sin filtros", new() },
    };

    [Theory]
    [MemberData(nameof(Exportaciones))]
    public void LaAccionAuditadaDeLaExportacionCumpleElContrato(string escenario, Dictionary<string, object?> detalle)
    {
        var accion = new AccionAuditada("EXPORTACION", "EXPORTACION_REGULATORIA",
            new Actor(Guid.NewGuid(), Rol.REGULADOR, null), "EXPORTACION", detalle);

        var comoViaja = JsonSerializer.Serialize(
            ClienteVistaLectura.SobreAccionAuditada(accion, IdCorrelacion.Nuevo()), ClienteVistaLectura.Json);

        var errores = ContratoEventos.Errores(comoViaja, "accion.auditada.v1");
        Assert.True(errores.Count == 0, $"{escenario}: {string.Join("; ", errores)}\n{comoViaja}");
    }

    private static (DateTimeOffset fecha, JsonElement datos) Leer(string tipo, string variante)
    {
        var json = ContratoEventos.Ejemplo(tipo, variante);
        Assert.Empty(ContratoEventos.Errores(json, $"{tipo}.v1"));
        using var doc = JsonDocument.Parse(json);
        return (doc.RootElement.GetProperty("fechaOcurrencia").GetDateTimeOffset(),
            doc.RootElement.GetProperty("datos").Clone());
    }

    [Theory]
    [MemberData(nameof(ContratoEventos.Variantes), MemberType = typeof(ContratoEventos))]
    public void ProyectaLosEventosDelContrato(string variante)
    {
        var idReporte = Guid.Parse("3fa85f64-5717-4562-b3fc-2c963f66afa6");

        var (f, d) = Leer("reporte.creado", variante);
        var reporte = ProyeccionEventos.Traducir("reporte.creado", f, d)!;
        Assert.Equal((idReporte, "ACUEDUCTO", "11001", "BARRIO"),
            (reporte.IdReporte, reporte.TipoServicio, reporte.CodigoMunicipio, reporte.Magnitud));
        Assert.Equal(4.6287, reporte.Latitud);
        Assert.NotNull(reporte.FechaRegistro);

        (f, d) = Leer("restablecimiento.registrado", variante);
        Assert.Equal(270, ProyeccionEventos.Traducir("restablecimiento.registrado", f, d)!.DuracionMinutos);

        (f, d) = Leer("validacion.registrada", variante);
        var validacion = ProyeccionEventos.Traducir("validacion.registrada", f, d)!;
        Assert.Equal((true, 3), (validacion.Validado, validacion.ConfirmacionesNetas));

        (f, d) = Leer("caso.actualizado", variante);
        var caso = ProyeccionEventos.Traducir("caso.actualizado", f, d)!;
        Assert.Equal(("EN_ATENCION", "ALTA"), (caso.EstadoCaso, caso.Prioridad));
        Assert.Equal(variante == "completo", caso.IdEmpresa is not null);

        (f, d) = Leer("caso.cerrado", variante);
        Assert.Equal(DateTimeOffset.Parse("2026-11-18T18:00:00Z"), ProyeccionEventos.Traducir("caso.cerrado", f, d)!.CerradoEn);
    }
}
