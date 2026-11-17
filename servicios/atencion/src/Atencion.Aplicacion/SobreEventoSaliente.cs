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

    public static SobreEventoSaliente Nuevo(string tipo, IdCorrelacion correlacion, DateTimeOffset ahora,
        IReadOnlyDictionary<string, object?> datos) =>
        new(Guid.NewGuid(), tipo, 1, ahora, Guid.Parse(correlacion.Valor), OrigenAtencion, datos);
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
                new Dictionary<string, object?>
                {
                    ["idCaso"] = antes.IdCaso.ToString(),
                    ["idReporte"] = antes.IdReporte.ToString(),
                    ["estadoAnterior"] = antes.Estado.ToString(),
                    ["estado"] = estado.ToString(),
                    ["prioridad"] = (cambios.Prioridad ?? antes.Prioridad).ToString(),
                    ["idEmpresaOperadora"] = (cambios.IdEmpresa ?? antes.IdEmpresa)?.ToString(),
                    ["fechaActualizacion"] = cambios.Fecha.ToString("O"),
                }));
        }
        if (estado == EstadoCaso.CERRADO && antes.Estado != EstadoCaso.CERRADO)
        {
            eventos.Add(SobreEventoSaliente.Nuevo("caso.cerrado", correlacion, cambios.Fecha,
                new Dictionary<string, object?>
                {
                    ["idCaso"] = antes.IdCaso.ToString(),
                    ["idReporte"] = antes.IdReporte.ToString(),
                    ["fechaCierre"] = cambios.Fecha.ToString("O"),
                    ["resolucion"] = cambios.Resolucion ?? antes.Resolucion,
                }));
        }
        return eventos;
    }
}
