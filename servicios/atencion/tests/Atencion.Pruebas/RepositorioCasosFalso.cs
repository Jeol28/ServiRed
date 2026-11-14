using System.Collections.Concurrent;
using ServiRed.Atencion.Aplicacion;
using ServiRed.Atencion.Aplicacion.Puertos;
using ServiRed.Atencion.Dominio;

namespace ServiRed.Atencion.Pruebas;

/// <summary>Doble de Persistencia: un caso por reporte, con fallas programables.</summary>
public sealed class RepositorioCasosFalso : IRepositorioCasos
{
    public readonly ConcurrentDictionary<Guid, Caso> Casos = new();
    public readonly ConcurrentDictionary<Guid, int> FallasTransitorias = new();
    public readonly ConcurrentBag<Guid> RechazosPermanentes = [];
    public readonly ConcurrentBag<string> Correlaciones = [];
    private int _llamadas;

    public int Llamadas => _llamadas;

    public Task<Caso> AbrirAsync(Caso caso, IdCorrelacion correlacion, CancellationToken ct)
    {
        Interlocked.Increment(ref _llamadas);
        Correlaciones.Add(correlacion.Valor);
        if (RechazosPermanentes.Contains(caso.IdReporte))
            throw new FallaPermanente("rechazado");
        if (FallasTransitorias.TryGetValue(caso.IdReporte, out var restantes) && restantes > 0)
        {
            FallasTransitorias[caso.IdReporte] = restantes - 1;
            throw new HttpRequestException("persistencia caída");
        }
        return Task.FromResult(Casos.GetOrAdd(caso.IdReporte, caso));
    }
}
