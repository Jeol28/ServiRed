using System.Collections.Concurrent;
using System.Text.Json;
using ServiRed.Auditoria.Aplicacion;
using ServiRed.Auditoria.Dominio;

namespace ServiRed.Auditoria.Pruebas;

/// <summary>Doble del registro de auditoría: idempotente por idEvento como el real.</summary>
public sealed class RepositorioFalso : IRepositorioAuditoria, IMetricasAlertas
{
    public readonly ConcurrentDictionary<Guid, RegistroAuditoria> Registros = new();
    public readonly ConcurrentQueue<Alerta> Metricas = new();

    public List<AnclaPublica> Anclas = [];

    public Task<IReadOnlyList<AnclaPublica>> AnclasAsync(IdCorrelacion correlacion, CancellationToken ct) =>
        Task.FromResult<IReadOnlyList<AnclaPublica>>(Anclas);

    public Task<bool> RegistrarAsync(RegistroAuditoria r, IdCorrelacion correlacion, CancellationToken ct) =>
        Task.FromResult(Registros.TryAdd(r.IdEvento, r));

    public FiltroAuditoria? UltimoFiltro;

    public Task<PaginaAuditoria> ConsultarAsync(FiltroAuditoria f, IdCorrelacion correlacion, CancellationToken ct)
    {
        UltimoFiltro = f;
        var lista = Registros.Values.Where(r => f.Categoria is null || r.Categoria == f.Categoria)
            .Select((r, i) => new RegistroConsultado(i, r.IdEvento, r.Tipo, r.Version, r.Origen, r.CorrelationId,
                r.Categoria, r.Accion, r.Resultado, r.ActorId, r.ActorRol, r.RecursoTipo, r.RecursoId,
                JsonSerializer.SerializeToElement(r.Detalle), r.OcurridoEn, DateTimeOffset.UtcNow, "h")).ToList();
        return Task.FromResult(new PaginaAuditoria(lista, lista.Count, f.Pagina, f.Tamano));
    }

    public Task<Verificacion> VerificarAsync(IdCorrelacion correlacion, CancellationToken ct) =>
        Task.FromResult(new Verificacion(Registros.Count, true, null, "h"));

    public void Registrar(Alerta alerta) => Metricas.Enqueue(alerta);
}
