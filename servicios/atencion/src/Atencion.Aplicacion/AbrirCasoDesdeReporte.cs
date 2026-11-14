using ServiRed.Atencion.Aplicacion.Eventos;
using ServiRed.Atencion.Aplicacion.Puertos;
using ServiRed.Atencion.Dominio;

namespace ServiRed.Atencion.Aplicacion;

/// <summary>
/// Al recibir reporte.creado abre el caso de atención en estado RECIBIDO
/// (RF-6). El enrutamiento a la empresa prestadora y la priorización llegan
/// en la fase 5.
/// </summary>
public sealed class AbrirCasoDesdeReporte(IRepositorioCasos casos, TimeProvider reloj)
{
    public Task<Caso> ManejarAsync(DatosReporteCreado datos, IdCorrelacion correlacion, CancellationToken ct)
    {
        Caso caso;
        try
        {
            caso = Caso.AbrirDesdeReporte(
                datos.IdReporte,
                Enum.Parse<TipoServicio>(datos.TipoServicio),
                datos.CodigoMunicipio,
                Enum.Parse<Magnitud>(datos.Magnitud),
                reloj.GetUtcNow());
        }
        catch (ArgumentException e)
        {
            throw new FallaPermanente("reporte.creado con datos inválidos: " + e.Message, e);
        }
        return casos.AbrirAsync(caso, correlacion, ct);
    }
}
