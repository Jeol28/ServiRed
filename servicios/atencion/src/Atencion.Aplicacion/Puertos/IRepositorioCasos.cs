using ServiRed.Atencion.Dominio;

namespace ServiRed.Atencion.Aplicacion.Puertos;

/// <summary>
/// Operaciones de negocio de Persistencia para el dominio de atención. Es
/// idempotente por X-Correlation-Id y hay a lo sumo un caso por reporte: si ya
/// existía, devuelve el caso original.
/// </summary>
public interface IRepositorioCasos
{
    Task<Caso> AbrirAsync(Caso caso, IdCorrelacion correlacion, CancellationToken ct);
}
