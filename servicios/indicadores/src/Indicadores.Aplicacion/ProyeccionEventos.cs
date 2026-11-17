using System.Text.Json;
using ServiRed.Indicadores.Dominio;

namespace ServiRed.Indicadores.Aplicacion;

/// <summary>
/// Consumidor de eventos (SAD, componentes de Indicadores): mantiene la vista de
/// lectura con reporte.creado, restablecimiento.registrado, validacion.registrada
/// (CORTE) y caso.*. No consume denuncia.recibida (ADR-09) ni guarda datos del
/// ciudadano. Es idempotente: reaplicar un evento no cambia la vista.
/// </summary>
public sealed class ProyeccionEventos(IVistaLectura vista)
{
    public static readonly IReadOnlySet<string> Tipos = new HashSet<string>
    {
        "reporte.creado", "restablecimiento.registrado", "validacion.registrada", "caso.actualizado", "caso.cerrado",
    };

    /// <returns>false si el evento no aporta a la vista</returns>
    public async Task<bool> AplicarAsync(string tipo, DateTimeOffset fechaEvento, JsonElement datos,
        IdCorrelacion correlacion, CancellationToken ct)
    {
        var actualizacion = Traducir(tipo, fechaEvento, datos);
        if (actualizacion is null) return false;
        await vista.ActualizarAsync(actualizacion, correlacion, ct);
        return true;
    }

    public static ActualizacionVista? Traducir(string tipo, DateTimeOffset fecha, JsonElement d)
    {
        try
        {
            var id = Guid.Parse(Texto(d, "idReporte") ?? throw new FallaPermanente($"{tipo} sin idReporte"));
            return tipo switch
            {
                "reporte.creado" => new ActualizacionVista(id, fecha,
                    TipoServicio: Texto(d, "tipoServicio"), CodigoMunicipio: Texto(d, "codigoMunicipio"),
                    Latitud: d.TryGetProperty("ubicacion", out var u) ? u.GetProperty("latitud").GetDouble() : null,
                    Longitud: d.TryGetProperty("ubicacion", out var u2) ? u2.GetProperty("longitud").GetDouble() : null,
                    Magnitud: Texto(d, "magnitud"), InicioEstimado: Fecha(d, "inicioEstimado"),
                    FechaRegistro: Fecha(d, "fechaRegistro")),
                "restablecimiento.registrado" => new ActualizacionVista(id, fecha,
                    FechaRestablecimiento: Fecha(d, "fechaRestablecimiento"),
                    DuracionMinutos: d.GetProperty("duracionMinutos").GetInt32()),
                "validacion.registrada" when Texto(d, "objeto") == "CORTE" => new ActualizacionVista(id, fecha,
                    Validado: d.GetProperty("validado").GetBoolean(),
                    ConfirmacionesNetas: d.GetProperty("confirmacionesNetas").GetInt32()),
                "caso.actualizado" => new ActualizacionVista(id, fecha,
                    EstadoCaso: Texto(d, "estado"), Prioridad: Texto(d, "prioridad"),
                    IdEmpresa: Guid.TryParse(Texto(d, "idEmpresaOperadora"), out var e) ? e : null,
                    ResueltoEn: Texto(d, "estado") == "RESUELTO" ? Fecha(d, "fechaActualizacion") ?? fecha : null),
                "caso.cerrado" => new ActualizacionVista(id, fecha,
                    EstadoCaso: "CERRADO", CerradoEn: Fecha(d, "fechaCierre") ?? fecha),
                _ => null,
            };
        }
        catch (Exception e) when (e is FormatException or KeyNotFoundException or InvalidOperationException)
        {
            throw new FallaPermanente($"datos de {tipo} inválidos", e);
        }
    }

    private static string? Texto(JsonElement d, string campo) =>
        d.TryGetProperty(campo, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;

    private static DateTimeOffset? Fecha(JsonElement d, string campo) =>
        Texto(d, campo) is { } t ? DateTimeOffset.Parse(t, System.Globalization.CultureInfo.InvariantCulture) : null;
}
