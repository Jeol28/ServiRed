using ServiRed.Atencion.Dominio;

namespace ServiRed.Atencion.Aplicacion;

/// <summary>Sobre común de eventos (contratos/eventos/esquemas/sobre-evento.v1.json).</summary>
public sealed record SobreEventoSaliente(
    Guid IdEvento,
    string Tipo,
    int Version,
    DateTimeOffset FechaOcurrencia,
    Guid CorrelationId,
    string Origen,
    IReadOnlyDictionary<string, object?> Datos)
{
    public const string OrigenAtencion = "atencion";

    /// <summary>
    /// Un campo opcional ausente se omite: el contrato no admite <c>null</c> (p. ej.
    /// idEmpresaOperadora de un caso sin empresa). Las opciones JSON del servicio omiten los
    /// null de las propiedades, pero no los de un diccionario como <c>datos</c>.
    /// </summary>
    public static SobreEventoSaliente Nuevo(string tipo, IdCorrelacion correlacion, DateTimeOffset ahora,
        IReadOnlyDictionary<string, object?> datos) =>
        new(Guid.NewGuid(), tipo, 1, ahora, Guid.Parse(correlacion.Valor), OrigenAtencion,
            datos.Where(d => d.Value is not null).ToDictionary(d => d.Key, d => d.Value));
}

/// <summary>Eventos del dominio de atención según sus esquemas versionados.</summary>
public static class EventosCaso
{
    /// <summary>
    /// caso.actualizado si cambia estado, prioridad, asignación o empresa; además
    /// caso.cerrado si el caso se cierra.
    /// </summary>
    public static IReadOnlyList<SobreEventoSaliente> Para(Caso antes, CambiosCaso cambios, IdCorrelacion correlacion)
    {
        var estado = cambios.Estado ?? antes.Estado;
        var cambia = cambios.CambiaEstado(antes.Estado)
                     || (cambios.Prioridad is not null && cambios.Prioridad != antes.Prioridad)
                     || cambios.AsignadoA is not null || cambios.IdEmpresa is not null;
        var eventos = new List<SobreEventoSaliente>();
        if (cambia)
        {
            eventos.Add(SobreEventoSaliente.Nuevo("caso.actualizado", correlacion, cambios.Fecha,
                ConActor(cambios.Autor, new Dictionary<string, object?>
                {
                    ["idCaso"] = antes.IdCaso.ToString(),
                    ["idReporte"] = antes.IdReporte.ToString(),
                    ["estadoAnterior"] = antes.Estado.ToString(),
                    ["estado"] = estado.ToString(),
                    ["prioridad"] = (cambios.Prioridad ?? antes.Prioridad).ToString(),
                    ["idEmpresaOperadora"] = (cambios.IdEmpresa ?? antes.IdEmpresa)?.ToString(),
                    ["fechaActualizacion"] = cambios.Fecha.ToString("O"),
                })));
        }
        if (estado == EstadoCaso.CERRADO && antes.Estado != EstadoCaso.CERRADO)
        {
            eventos.Add(SobreEventoSaliente.Nuevo("caso.cerrado", correlacion, cambios.Fecha,
                ConActor(cambios.Autor, new Dictionary<string, object?>
                {
                    ["idCaso"] = antes.IdCaso.ToString(),
                    ["idReporte"] = antes.IdReporte.ToString(),
                    ["fechaCierre"] = cambios.Fecha.ToString("O"),
                    ["resolucion"] = cambios.Resolucion ?? antes.Resolucion,
                })));
        }
        return eventos;
    }

    /// <summary>
    /// caso.actualizado de la apertura: estado RECIBIDO, la prioridad calculada y la empresa
    /// enrutada, sin estadoAnterior (no lo había) ni idActor (es automática). Sin él, la vista de
    /// Indicadores no conocía el estado, la prioridad ni la empresa hasta el primer cambio del
    /// caso, y los mapas y exportaciones por empresa dejaban fuera esos reportes.
    /// </summary>
    public static SobreEventoSaliente Apertura(Caso caso, IdCorrelacion correlacion) =>
        SobreEventoSaliente.Nuevo("caso.actualizado", correlacion, caso.AbiertoEn, new Dictionary<string, object?>
        {
            ["idCaso"] = caso.IdCaso.ToString(),
            ["idReporte"] = caso.IdReporte.ToString(),
            ["estado"] = caso.Estado.ToString(),
            ["prioridad"] = caso.Prioridad.ToString(),
            ["idEmpresaOperadora"] = caso.IdEmpresa?.ToString(),
            ["fechaActualizacion"] = caso.AbiertoEn.ToString("O"),
        });

    /// <summary>idActor (opcional en caso.* v1) solo cuando lo hizo una persona; ausente si fue automático.</summary>
    private static Dictionary<string, object?> ConActor(Guid? autor, Dictionary<string, object?> datos)
    {
        if (autor is not null) datos["idActor"] = autor.ToString();
        return datos;
    }

    /** accion.auditada v1 de una decisión de moderación (RF-12). */
    public static SobreEventoSaliente DenunciaModerada(Actor actor, Guid idDenuncia, EstadoModeracion decision,
        IdCorrelacion correlacion, DateTimeOffset fecha) =>
        SobreEventoSaliente.Nuevo("accion.auditada", correlacion, fecha, new Dictionary<string, object?>
        {
            ["categoria"] = "MODERACION",
            ["accion"] = "DENUNCIA_MODERADA",
            ["resultado"] = "EXITO",
            ["actor"] = new Dictionary<string, object?> { ["idCuenta"] = actor.IdCuenta.ToString(), ["rol"] = actor.Rol.ToString() },
            ["recurso"] = new Dictionary<string, object?> { ["tipo"] = "DENUNCIA", ["id"] = idDenuncia.ToString() },
            ["detalle"] = new Dictionary<string, object?> { ["decision"] = decision.ToString() },
        });
}
