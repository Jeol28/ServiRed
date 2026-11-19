using ServiRed.Auditoria.Dominio;

namespace ServiRed.Auditoria.Aplicacion;

/// <summary>
/// Registra cada evento de dominio en la auditoría (RF-12) y evalúa las reglas
/// de alerta de seguridad (RF-13); las alertas también quedan en la auditoría.
/// </summary>
public sealed class RegistroDeAuditoria(IRepositorioAuditoria repositorio, DetectorAlertas detector, IMetricasAlertas metricas)
{
    /// <returns>false si el tipo de evento no se audita</returns>
    public async Task<bool> RegistrarEventoAsync(SobreEvento evento, IdCorrelacion correlacion, CancellationToken ct)
    {
        RegistroAuditoria? registro;
        try
        {
            registro = Clasificador.Clasificar(evento);
        }
        catch (DatosInvalidos e)
        {
            throw new FallaPermanente(e.Message, e);
        }
        if (registro is null) return false;
        var nuevo = await repositorio.RegistrarAsync(registro, correlacion, ct);
        // Una redelivery (nuevo = false) no vuelve a contar para las reglas.
        if (nuevo)
            foreach (var alerta in detector.Evaluar(registro))
            {
                await repositorio.RegistrarAsync(Registro(alerta, correlacion), correlacion, ct);
                metricas.Registrar(alerta);
            }
        return true;
    }

    /// <summary>Alertas operativas de Alertmanager (servicio caído, DLQ, outbox, errores...).</summary>
    public async Task<int> RegistrarAlertasExternasAsync(IEnumerable<AlertaExterna> alertas, IdCorrelacion correlacion,
        CancellationToken ct)
    {
        var registradas = 0;
        foreach (var a in alertas)
        {
            var detalle = new Dictionary<string, object?>
            {
                ["estado"] = a.Estado, ["severidad"] = a.Severidad, ["servicio"] = a.Servicio, ["resumen"] = a.Resumen,
            };
            var registro = new RegistroAuditoria(GuidDeterminista.De($"alertmanager|{a.Huella}|{a.Inicio:O}|{a.Estado}"),
                "alerta.operativa", 1, "alertmanager", Guid.Parse(correlacion.Valor), Categorias.Alerta, a.Nombre,
                a.Estado == "resolved" ? "EXITO" : "FALLO", null, null, "SERVICIO", a.Servicio, detalle, a.Inicio);
            if (await repositorio.RegistrarAsync(registro, correlacion, ct)) registradas++;
        }
        return registradas;
    }

    private static RegistroAuditoria Registro(Alerta a, IdCorrelacion c) =>
        new(GuidDeterminista.De($"{a.Clave}|{a.Fecha:O}"), "alerta.seguridad", 1, "auditoria", Guid.Parse(c.Valor),
            Categorias.Alerta, a.Tipo, "FALLO", null, null, "ALERTA", a.Clave,
            new Dictionary<string, object?>(a.Detalle) { ["severidad"] = a.Severidad, ["descripcion"] = a.Descripcion },
            a.Fecha);
}

/// <summary>Consulta del registro (RF-12): regulador y administrador; la verificación de integridad, solo administrador.</summary>
public sealed class ConsultasAuditoria(IRepositorioAuditoria repositorio)
{
    public Task<PaginaAuditoria> ConsultarAsync(Actor actor, FiltroAuditoria filtro, IdCorrelacion c, CancellationToken ct)
    {
        if (actor.Rol is not (Rol.REGULADOR or Rol.ADMINISTRADOR)) throw new AccesoDenegado("Rol sin acceso a la auditoría");
        if (filtro.Categoria is not null && !Categorias.Todas.Contains(filtro.Categoria))
            throw new DatosInvalidos("categoria", "categoría desconocida");
        return repositorio.ConsultarAsync(filtro with { Tamano = Math.Clamp(filtro.Tamano, 1, 200) }, c, ct);
    }

    public Task<Verificacion> VerificarAsync(Actor actor, IdCorrelacion c, CancellationToken ct)
    {
        if (actor.Rol != Rol.ADMINISTRADOR) throw new AccesoDenegado("Solo un administrador verifica la integridad");
        return repositorio.VerificarAsync(c, ct);
    }

    /// <summary>Anclas de la cadena (ADR-31): públicas, para que cualquiera las guarde y compare.</summary>
    public Task<IReadOnlyList<AnclaPublica>> AnclasAsync(IdCorrelacion c, CancellationToken ct) =>
        repositorio.AnclasAsync(c, ct);
}
