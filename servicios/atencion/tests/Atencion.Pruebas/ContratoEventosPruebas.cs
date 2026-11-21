using System.Text.Json;
using ServiRed.Atencion.Aplicacion;
using ServiRed.Atencion.Aplicacion.Eventos;
using ServiRed.Atencion.Dominio;
using ServiRed.Atencion.Infraestructura.Mensajeria;
using ServiRed.Atencion.Infraestructura.Persistencia;

namespace ServiRed.Atencion.Pruebas;

/// <summary>
/// Atención contra el contrato de eventos (contratos/eventos):
/// <list type="bullet">
/// <item>como productor, caso.actualizado y caso.cerrado, serializados
/// como viajan (las opciones JSON del servicio), cumplen su JSON Schema; también un caso sin
/// empresa y un cierre sin resolución;</item>
/// <item>como consumidor, lee los ejemplos del contrato de los eventos de Reportes (Java),
/// también los mínimos sin campos opcionales.</item>
/// </list>
/// </summary>
public class ContratoEventosPruebas
{
    private static readonly DateTimeOffset Ahora = DateTimeOffset.Parse("2026-11-18T15:04:05.123Z");
    private static readonly IdCorrelacion Correlacion = IdCorrelacion.Nuevo();
    private static readonly Actor Admin = new(Guid.NewGuid(), Rol.ADMINISTRADOR, null);
    private static readonly Guid Empresa = Guid.Parse("a0000000-0000-4000-8000-000000000001");

    private static Caso Nuevo(Guid? empresa) =>
        Caso.AbrirDesdeReporte(Guid.NewGuid(), TipoServicio.ENERGIA, "11001", Magnitud.CUADRA, Ahora, empresa);

    public static TheoryData<string, SobreEventoSaliente[]> EventosProducidos()
    {
        var enrutado = Nuevo(Empresa);
        var sinEmpresa = Nuevo(null);
        var resuelto = enrutado with { Estado = EstadoCaso.RESUELTO, Resolucion = "Se cambió el fusible" };
        var resueltoSinResolucion = sinEmpresa with { Estado = EstadoCaso.RESUELTO };
        return new()
        {
            { "abrir un caso enrutado", [EventosCaso.Apertura(enrutado, Correlacion)] },
            { "abrir un caso sin empresa", [EventosCaso.Apertura(sinEmpresa, Correlacion)] },
            { "atender un caso enrutado", [.. EventosCaso.Para(enrutado, enrutado.Atender(Admin, Ahora), Correlacion)] },
            { "atender un caso sin empresa", [.. EventosCaso.Para(sinEmpresa, sinEmpresa.Atender(Admin, Ahora), Correlacion)] },
            { "cerrar con resolución", [.. EventosCaso.Para(resuelto, resuelto.Cerrar(Admin, null, Ahora), Correlacion)] },
            { "cerrar sin resolución", [.. EventosCaso.Para(resueltoSinResolucion,
                resueltoSinResolucion.Cerrar(Admin, null, Ahora), Correlacion)] },
            { "resolver por restablecimiento (automático)", [.. EventosCaso.Para(sinEmpresa,
                sinEmpresa.ResolverPorRestablecimiento(90, Ahora)!, Correlacion)] },
            { "enrutar a una empresa", [.. EventosCaso.Para(sinEmpresa, sinEmpresa.Enrutar(Admin, Empresa, Ahora), Correlacion)] },
        };
    }

    [Theory]
    [MemberData(nameof(EventosProducidos))]
    public void CadaEventoQueProduceCumpleSuEsquema(string escenario, SobreEventoSaliente[] eventos)
    {
        Assert.NotEmpty(eventos);
        foreach (var e in eventos)
        {
            var comoViaja = JsonSerializer.Serialize(e, ClientePersistencia.Json);
            var errores = ContratoEventos.Errores(comoViaja, $"{e.Tipo}.v{e.Version}");
            Assert.True(errores.Count == 0, $"{escenario} → {e.Tipo}: {string.Join("; ", errores)}\n{comoViaja}");
        }
    }

    // ------------------------------------------------------------------ consumidor

    private static (SobreEvento sobre, JsonElement datos) Leer(string tipo, string variante)
    {
        var json = ContratoEventos.Ejemplo(tipo, variante);
        Assert.Empty(ContratoEventos.Errores(json, $"{tipo}.v1"));
        var sobre = JsonSerializer.Deserialize<SobreEvento>(json, ClientePersistencia.Json)!;
        return (sobre, sobre.Datos);
    }

    [Theory]
    [MemberData(nameof(ContratoEventos.Variantes), MemberType = typeof(ContratoEventos))]
    public void LeeReporteCreadoDelContrato(string variante)
    {
        var (sobre, datos) = Leer("reporte.creado", variante);
        var d = datos.Deserialize<DatosReporteCreado>(ClientePersistencia.Json)!;

        Assert.Equal("reporte.creado", sobre.Tipo);
        Assert.Equal(Guid.Parse(datos.GetProperty("idReporte").GetString()!), d.IdReporte);
        Assert.Equal("ACUEDUCTO", d.TipoServicio);
        Assert.Equal("11001", d.CodigoMunicipio);
        Assert.Equal("BARRIO", d.Magnitud);
        Assert.Equal(4.6287, d.Ubicacion!.Latitud);
        Assert.Equal(DateTimeOffset.Parse("2026-11-18T13:00:00Z"), d.InicioEstimado);
        Assert.Equal(variante == "completo", d.Descripcion is not null);
    }

    [Theory]
    [MemberData(nameof(ContratoEventos.Variantes), MemberType = typeof(ContratoEventos))]
    public void LeeRestablecimientoValidacionYDenunciaDelContrato(string variante)
    {
        var r = Leer("restablecimiento.registrado", variante).datos.Deserialize<DatosRestablecimiento>(ClientePersistencia.Json)!;
        Assert.Equal(270, r.DuracionMinutos);
        Assert.Equal(DateTimeOffset.Parse("2026-11-18T17:30:00Z"), r.FechaRestablecimiento);

        var v = Leer("validacion.registrada", variante).datos.Deserialize<DatosValidacion>(ClientePersistencia.Json)!;
        Assert.Equal(("CORTE", "CONFIRMA", 3, true), (v.Objeto, v.Sentido, v.ConfirmacionesNetas, v.Validado));

        var d = Leer("denuncia.recibida", variante).datos.Deserialize<DatosDenuncia>(ClientePersistencia.Json)!;
        Assert.Equal(("ENERGIA", "08001"), (d.TipoServicio, d.CodigoMunicipio));
        Assert.Equal(variante == "completo", d.TieneEvidencia);
    }
}
