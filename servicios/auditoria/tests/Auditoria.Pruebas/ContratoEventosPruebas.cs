using System.Text.Json;
using ServiRed.Auditoria.Dominio;
using ServiRed.Auditoria.Infraestructura.Persistencia;

namespace ServiRed.Auditoria.Pruebas;

/// <summary>
/// Auditoría consume todos los eventos («#»): lee cada ejemplo del contrato, sea de un
/// productor Java (Reportes, Identidad) o .NET (Atención, Indicadores), completo o mínimo,
/// y lo clasifica en una entrada de auditoría.
/// </summary>
public class ContratoEventosPruebas
{
    public static TheoryData<string, string, string, string, string> Eventos()
    {
        var datos = new TheoryData<string, string, string, string, string>();
        var esperados = new (string tipo, string categoria, string accion, string recurso)[]
        {
            ("reporte.creado", "REPORTE", "REPORTE_CREADO", "REPORTE"),
            ("restablecimiento.registrado", "REPORTE", "RESTABLECIMIENTO_REGISTRADO", "REPORTE"),
            ("validacion.registrada", "VALIDACION", "VALIDACION_REGISTRADA", "REPORTE"),
            ("denuncia.recibida", "REPORTE", "DENUNCIA_RECIBIDA", "DENUNCIA"),
            ("caso.actualizado", "CAMBIO_ESTADO", "CASO_ACTUALIZADO", "CASO"),
            ("caso.cerrado", "CAMBIO_ESTADO", "CASO_CERRADO", "CASO"),
            ("accion.auditada", "ADMINISTRACION", "CUENTA_CREADA", "CUENTA"),
        };
        foreach (var (tipo, categoria, accion, recurso) in esperados)
            foreach (var variante in new[] { "completo", "minimo" })
                datos.Add(tipo, variante, categoria, accion, recurso);
        return datos;
    }

    [Theory]
    [MemberData(nameof(Eventos))]
    public void ClasificaCadaEventoDelContrato(string tipo, string variante, string categoria, string accion,
        string recurso)
    {
        var json = ContratoEventos.Ejemplo(tipo, variante);
        Assert.Empty(ContratoEventos.Errores(json, $"{tipo}.v1"));

        var sobre = JsonSerializer.Deserialize<SobreEvento>(json, ClientePersistencia.Json)!;
        var registro = Clasificador.Clasificar(sobre)!;

        Assert.Equal((tipo, categoria, accion), (registro.Tipo, registro.Categoria, registro.Accion));
        if (tipo == "accion.auditada" && variante == "minimo")
        {
            Assert.Null(registro.RecursoTipo);   // el mínimo no trae recurso ni actor
            Assert.Null(registro.ActorId);
        }
        else
        {
            Assert.Equal(recurso, registro.RecursoTipo);
            Assert.NotNull(registro.RecursoId);
        }
    }
}
