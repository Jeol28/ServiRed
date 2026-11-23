using ServiRed.Atencion.Aplicacion.Eventos;
using ServiRed.Atencion.Aplicacion.Puertos;
using ServiRed.Atencion.Dominio;

namespace ServiRed.Atencion.Aplicacion;

/// <summary>
/// Consumidor de eventos de reportes (SAD, componentes de Atención): crea o
/// actualiza el caso al recibir reporte.creado, restablecimiento.registrado y
/// validacion.registrada, y abre la moderación de denuncia.recibida. Todo es
/// idempotente: una redelivery no duplica casos ni cambios.
/// </summary>
public sealed class ReaccionesAEventos(
    IRepositorioCasos casos,
    ICatalogoAtencion catalogo,
    IRepositorioModeracion moderacion,
    TimeProvider reloj)
{
    private const int IntentosPorConcurrencia = 3;

    /// <summary>
    /// reporte.creado: abre el caso enrutado a la única empresa dueña de la zona del reporte
    /// (ADR-35) y priorizado. Si nadie presta el servicio allí, queda sin empresa.
    /// </summary>
    public async Task<Caso> AbrirCasoAsync(DatosReporteCreado d, IdCorrelacion correlacion, CancellationToken ct)
    {
        var (servicio, magnitud) = Parsear(d.TipoServicio, d.Magnitud);
        var zona = await catalogo.CoberturaAsync(new ConsultaCobertura(servicio, d.Ubicacion?.Latitud,
            d.Ubicacion?.Longitud, null, d.CodigoMunicipio), correlacion, ct);
        var empresa = zona?.IdEmpresa;
        Caso caso;
        try
        {
            caso = Caso.AbrirDesdeReporte(d.IdReporte, servicio, d.CodigoMunicipio, magnitud, reloj.GetUtcNow(), empresa,
                d.Ubicacion?.Latitud, d.Ubicacion?.Longitud, d.Descripcion, d.InicioEstimado);
        }
        catch (ArgumentException e)
        {
            throw new FallaPermanente("reporte.creado con datos inválidos: " + e.Message, e);
        }
        return await casos.AbrirAsync(caso, [EventosCaso.Apertura(caso, correlacion)], correlacion, ct);
    }

    /// <summary>restablecimiento.registrado: el caso abierto pasa a RESUELTO y se publica caso.actualizado.</summary>
    public Task<Caso?> RegistrarRestablecimientoAsync(DatosRestablecimiento d, IdCorrelacion correlacion,
        CancellationToken ct) =>
        AplicarAsync(d.IdReporte, c => c.ResolverPorRestablecimiento(d.DuracionMinutos, reloj.GetUtcNow()), correlacion, ct);

    /// <summary>validacion.registrada del CORTE: marca la validación y recalcula la prioridad.</summary>
    public Task<Caso?> RegistrarValidacionAsync(DatosValidacion d, IdCorrelacion correlacion, CancellationToken ct) =>
        d.Objeto == "CORTE"
            ? AplicarAsync(d.IdReporte, c => c.RegistrarValidacionDelCorte(d.Validado, reloj.GetUtcNow()), correlacion, ct)
            : Task.FromResult<Caso?>(null);

    /// <summary>
    /// denuncia.recibida: abre la moderación en la empresa dueña de la zona de la denuncia. El
    /// evento no trae la ubicación (anonimato); Persistencia usa la que guardó.
    /// </summary>
    public async Task<Moderacion> AbrirModeracionAsync(DatosDenuncia d, IdCorrelacion correlacion, CancellationToken ct)
    {
        var (servicio, _) = Parsear(d.TipoServicio, "VIVIENDA");
        var zona = await catalogo.CoberturaAsync(new ConsultaCobertura(servicio, IdDenuncia: d.IdDenuncia,
            CodigoMunicipio: d.CodigoMunicipio), correlacion, ct);
        return await moderacion.AbrirAsync(d.IdDenuncia, zona?.IdEmpresa, d.FechaRecepcion, correlacion, ct);
    }

    /// <summary>
    /// Lee el caso, decide el cambio en el dominio y lo guarda con concurrencia
    /// optimista; ante un conflicto vuelve a leer. Si el caso aún no existe (el
    /// evento llegó antes que reporte.creado) es una falla transitoria: se reintenta.
    /// </summary>
    private async Task<Caso?> AplicarAsync(Guid idReporte, Func<Caso, CambiosCaso?> decidir, IdCorrelacion correlacion,
        CancellationToken ct)
    {
        for (var intento = 1; ; intento++)
        {
            var caso = await casos.BuscarPorReporteAsync(idReporte, correlacion, ct)
                       ?? throw new InvalidOperationException("El caso del reporte aún no existe");
            var cambios = decidir(caso);
            if (cambios is null) return caso;
            try
            {
                return await casos.ActualizarAsync(caso.IdCaso, cambios, EventosCaso.Para(caso, cambios, correlacion),
                    intento == 1 ? correlacion : IdCorrelacion.Nuevo(), ct);
            }
            catch (ConflictoDeVersion) when (intento < IntentosPorConcurrencia)
            {
            }
        }
    }

    private static (TipoServicio, Magnitud) Parsear(string servicio, string magnitud)
    {
        if (!Enum.TryParse<TipoServicio>(servicio, out var s) || !Enum.TryParse<Magnitud>(magnitud, out var m))
            throw new FallaPermanente($"Servicio o magnitud desconocidos: {servicio}/{magnitud}");
        return (s, m);
    }
}
