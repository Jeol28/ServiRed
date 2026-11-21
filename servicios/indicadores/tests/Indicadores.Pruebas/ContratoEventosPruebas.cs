using System.Text.Json;
using ServiRed.Indicadores.Aplicacion;
using ServiRed.Indicadores.Dominio;
using ServiRed.Indicadores.Infraestructura.Persistencia;

namespace ServiRed.Indicadores.Pruebas;

/// <summary>
/// Indicadores contra el contrato de eventos (contratos/eventos): como consumidor, la
/// proyección lee los ejemplos del contrato de Reportes (Java) y Atención (.NET), también
/// los mínimos sin campos opcionales.
/// </summary>
public class ContratoEventosPruebas
{
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
