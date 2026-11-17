namespace ServiRed.Auditoria.Dominio;

/// <summary>Alerta de seguridad u operación (RF-13).</summary>
public sealed record Alerta(string Tipo, string Severidad, string Descripcion, string Clave, DateTimeOffset Fecha,
    IReadOnlyDictionary<string, object?> Detalle);

/// <summary>
/// Monitor de seguridad sobre el flujo de auditoría (RF-13, ADR-16). Reglas:
/// <list type="bullet">
/// <item>FUERZA_BRUTA_CUENTA: 5 inicios de sesión fallidos sobre la misma cuenta en 10 min.</item>
/// <item>FALLOS_ACCESO_MASIVOS: 20 fallidos en total en 5 min (ataque distribuido).</item>
/// <item>TOKEN_REUTILIZADO: cualquier reutilización de un token de refresco (posible robo).</item>
/// <item>EXPORTACIONES_INUSUALES: 10 exportaciones regulatorias de la misma cuenta en 1 h.</item>
/// </list>
/// Cada regla alerta una sola vez por ventana. El estado vive en memoria: si el
/// servicio se reinicia, las ventanas empiezan de cero (aceptable para el MVP).
/// </summary>
public sealed class DetectorAlertas
{
    public sealed record Regla(string Tipo, string Severidad, int Umbral, TimeSpan Ventana);

    public static readonly Regla FuerzaBruta = new("FUERZA_BRUTA_CUENTA", "ALTA", 5, TimeSpan.FromMinutes(10));
    public static readonly Regla FallosMasivos = new("FALLOS_ACCESO_MASIVOS", "CRITICA", 20, TimeSpan.FromMinutes(5));
    public static readonly Regla TokenReutilizado = new("TOKEN_REUTILIZADO", "ALTA", 1, TimeSpan.FromMinutes(1));
    public static readonly Regla ExportacionesInusuales = new("EXPORTACIONES_INUSUALES", "MEDIA", 10, TimeSpan.FromHours(1));

    private readonly Dictionary<string, Queue<DateTimeOffset>> _ventanas = new();
    private readonly Dictionary<string, DateTimeOffset> _alertadas = new();
    private readonly object _candado = new();

    public IReadOnlyList<Alerta> Evaluar(RegistroAuditoria r)
    {
        var alertas = new List<Alerta>();
        lock (_candado)
        {
            switch (r.Accion)
            {
                case "INICIO_SESION_FALLIDO":
                    if (r.RecursoId is not null)
                        Contar(FuerzaBruta, r.RecursoId, r, alertas, "Inicios de sesión fallidos repetidos sobre una cuenta");
                    Contar(FallosMasivos, "*", r, alertas, "Pico de inicios de sesión fallidos en la plataforma");
                    break;
                case "REFRESCO_REUTILIZADO":
                    Contar(TokenReutilizado, r.IdEvento.ToString(), r, alertas,
                        "Se reutilizó un token de refresco; se revocaron las sesiones de la cuenta");
                    break;
                case "EXPORTACION_REGULATORIA" when r.ActorId is not null:
                    Contar(ExportacionesInusuales, r.ActorId.ToString()!, r, alertas,
                        "Volumen inusual de exportaciones regulatorias de una misma cuenta");
                    break;
            }
        }
        return alertas;
    }

    private void Contar(Regla regla, string clave, RegistroAuditoria r, List<Alerta> alertas, string descripcion)
    {
        var llave = regla.Tipo + "|" + clave;
        if (!_ventanas.TryGetValue(llave, out var marcas)) _ventanas[llave] = marcas = new Queue<DateTimeOffset>();
        marcas.Enqueue(r.OcurridoEn);
        while (marcas.Count > 0 && marcas.Peek() <= r.OcurridoEn - regla.Ventana) marcas.Dequeue();
        if (marcas.Count < regla.Umbral) return;
        if (_alertadas.TryGetValue(llave, out var ultima) && r.OcurridoEn - ultima < regla.Ventana) return;
        _alertadas[llave] = r.OcurridoEn;
        var detalle = new Dictionary<string, object?>
        {
            ["umbral"] = regla.Umbral, ["ventanaMinutos"] = regla.Ventana.TotalMinutes, ["conteo"] = marcas.Count,
            ["eventoDisparador"] = r.IdEvento.ToString(),
        };
        if (clave != "*") detalle["clave"] = clave;
        alertas.Add(new Alerta(regla.Tipo, regla.Severidad, descripcion, llave, r.OcurridoEn, detalle));
    }
}
