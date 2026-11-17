using System.Collections.Concurrent;
using ServiRed.Indicadores.Aplicacion;
using ServiRed.Indicadores.Dominio;

namespace ServiRed.Indicadores.Pruebas;

/// <summary>Doble de la vista de lectura: registra las actualizaciones y devuelve agregados y hechos programados.</summary>
public sealed class VistaFalsa : IVistaLectura
{
    public readonly ConcurrentQueue<ActualizacionVista> Actualizaciones = new();
    public readonly ConcurrentQueue<(Agrupacion, FiltroVista)> Consultas = new();
    public Dictionary<Agrupacion, List<Agregado>> Agregados = new();
    public List<Hecho> Hechos = [];

    public Task ActualizarAsync(ActualizacionVista a, IdCorrelacion correlacion, CancellationToken ct)
    {
        Actualizaciones.Enqueue(a);
        return Task.CompletedTask;
    }

    public Task<IReadOnlyList<Agregado>> AgregarAsync(FiltroVista filtro, Agrupacion agrupacion, double celdaGrados,
        IdCorrelacion correlacion, CancellationToken ct)
    {
        Consultas.Enqueue((agrupacion, filtro));
        return Task.FromResult<IReadOnlyList<Agregado>>(Agregados.GetValueOrDefault(agrupacion) ?? []);
    }

    public Task<PaginaHechos> HechosAsync(FiltroVista filtro, int desde, int limite, IdCorrelacion correlacion,
        CancellationToken ct)
    {
        var lista = Hechos.Where(h => filtro.IdEmpresa is null || h.IdEmpresa == filtro.IdEmpresa).ToList();
        var pagina = lista.Skip(desde).Take(limite).ToList();
        return Task.FromResult(new PaginaHechos(pagina, desde + limite < lista.Count));
    }

    public Task<EstadoVista> EstadoAsync(IdCorrelacion correlacion, CancellationToken ct) =>
        Task.FromResult(new EstadoVista(10, 2, DateTimeOffset.UtcNow.AddDays(-5), DateTimeOffset.UtcNow, DateTimeOffset.UtcNow));

    public static Agregado Grupo(long reportes, long conDuracion = 0, string? codigo = "11001", string? servicio = null,
        double? lat = null, double? lon = null) =>
        new(codigo, "BOGOTÁ, D.C.", "BOGOTÁ, D.C.", servicio, new DateTimeOffset(2026, 11, 1, 0, 0, 0, TimeSpan.Zero),
            lat, lon, reportes, conDuracion, conDuracion > 0 ? 100 : null, conDuracion > 0 ? 90 : null,
            conDuracion > 0 ? 200 : null, reportes, 0, 0, 0, reportes / 2, reportes / 2, 60);

    public static Hecho Hecho(Guid? empresa = null) =>
        new(Guid.NewGuid(), "ENERGIA", "11001", "BOGOTÁ, D.C.", "BOGOTÁ, D.C.", 4.628734, -74.064821, "CUADRA",
            DateTimeOffset.UtcNow.AddHours(-3), DateTimeOffset.UtcNow.AddHours(-2), null, null, false, 0, "RECIBIDO",
            "MEDIA", empresa, null, null);
}
