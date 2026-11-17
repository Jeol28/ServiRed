using System.Text.Json;

namespace ServiRed.Auditoria.Dominio;

/// <summary>Sobre común de eventos (contratos/eventos/esquemas/sobre-evento.v1.json).</summary>
public sealed record SobreEvento(Guid IdEvento, string Tipo, int Version, DateTimeOffset FechaOcurrencia,
    Guid CorrelationId, string Origen, JsonElement Datos);

/// <summary>
/// Traduce cada evento de dominio en una entrada de auditoría (RF-12): cambios de
/// estado, validaciones, reportes y denuncias, y las acciones de accion.auditada
/// (accesos, administración, exportaciones, moderación, seguridad).
/// </summary>
public static class Clasificador
{
    public static RegistroAuditoria? Clasificar(SobreEvento e)
    {
        var d = e.Datos;
        string? Texto(string campo) =>
            d.ValueKind == JsonValueKind.Object && d.TryGetProperty(campo, out var v) && v.ValueKind == JsonValueKind.String
                ? v.GetString() : null;
        Guid? Id(string? valor) => Guid.TryParse(valor, out var g) ? g : null;
        RegistroAuditoria Registro(string categoria, string accion, string? resultado, Guid? actor, string? rol,
            string? recursoTipo, string? recursoId, IReadOnlyDictionary<string, object?> detalle) =>
            new(e.IdEvento, e.Tipo, e.Version, e.Origen, e.CorrelationId, categoria, accion, resultado, actor, rol,
                recursoTipo, recursoId, detalle, e.FechaOcurrencia);

        return e.Tipo switch
        {
            "reporte.creado" => Registro(Categorias.Reporte, "REPORTE_CREADO", "EXITO", null, null, "REPORTE",
                Texto("idReporte"), Sanitizador.Seleccionar(d, "tipoServicio", "codigoMunicipio", "magnitud", "tieneEvidencia")),
            "restablecimiento.registrado" => Registro(Categorias.Reporte, "RESTABLECIMIENTO_REGISTRADO", "EXITO", null,
                null, "REPORTE", Texto("idReporte"), Sanitizador.Seleccionar(d, "duracionMinutos", "fechaRestablecimiento")),
            "validacion.registrada" => Registro(Categorias.Validacion, "VALIDACION_REGISTRADA", "EXITO", null, null,
                "REPORTE", Texto("idReporte"), Sanitizador.Seleccionar(d, "objeto", "sentido", "confirmacionesNetas", "validado")),
            "denuncia.recibida" => Registro(Categorias.Reporte, "DENUNCIA_RECIBIDA", "EXITO", null, null, "DENUNCIA",
                Texto("idDenuncia"), Sanitizador.Seleccionar(d, "tipoServicio", "codigoMunicipio", "tieneEvidencia")),
            "caso.actualizado" => Registro(Categorias.CambioEstado, "CASO_ACTUALIZADO", "EXITO", Id(Texto("idActor")),
                null, "CASO", Texto("idCaso"),
                Sanitizador.Seleccionar(d, "idReporte", "estadoAnterior", "estado", "prioridad", "idEmpresaOperadora")),
            "caso.cerrado" => Registro(Categorias.CambioEstado, "CASO_CERRADO", "EXITO", Id(Texto("idActor")), null,
                "CASO", Texto("idCaso"), Sanitizador.Seleccionar(d, "idReporte", "fechaCierre")),
            "accion.auditada" => AccionAuditada(e, d, Registro),
            _ => null,
        };
    }

    private static RegistroAuditoria? AccionAuditada(SobreEvento e, JsonElement d,
        Func<string, string, string?, Guid?, string?, string?, string?, IReadOnlyDictionary<string, object?>, RegistroAuditoria> registro)
    {
        string? Campo(JsonElement o, string c) =>
            o.ValueKind == JsonValueKind.Object && o.TryGetProperty(c, out var v) && v.ValueKind == JsonValueKind.String
                ? v.GetString() : null;
        var categoria = Campo(d, "categoria");
        var accion = Campo(d, "accion");
        if (categoria is null || accion is null || !Categorias.Todas.Contains(categoria) || categoria == Categorias.Alerta)
            throw new DatosInvalidos("datos", "accion.auditada sin categoría o acción válidas");
        var actor = d.TryGetProperty("actor", out var a) ? a : default;
        var recurso = d.TryGetProperty("recurso", out var r) ? r : default;
        return registro(categoria, accion, Campo(d, "resultado"),
            Guid.TryParse(Campo(actor, "idCuenta"), out var idActor) ? idActor : null, Campo(actor, "rol"),
            Campo(recurso, "tipo"), Campo(recurso, "id"),
            d.TryGetProperty("detalle", out var detalle) ? Sanitizador.Sanear(detalle) : new Dictionary<string, object?>());
    }
}
