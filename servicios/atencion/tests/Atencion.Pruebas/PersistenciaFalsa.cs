using System.Collections.Concurrent;
using ServiRed.Atencion.Aplicacion;
using ServiRed.Atencion.Aplicacion.Puertos;
using ServiRed.Atencion.Dominio;

namespace ServiRed.Atencion.Pruebas;

/// <summary>
/// Doble de Persistencia con la misma semántica: un caso por reporte, versión
/// optimista, eventos en "outbox" y fallas transitorias o permanentes programables.
/// </summary>
public sealed class PersistenciaFalsa : IRepositorioCasos, ICatalogoAtencion, IRepositorioModeracion
{
    public static readonly Guid EmpresaA = Guid.Parse("a0000000-0000-4000-8000-000000000001");
    public static readonly Guid EmpresaB = Guid.Parse("a0000000-0000-4000-8000-000000000002");

    public readonly ConcurrentDictionary<Guid, Caso> Casos = new();
    public readonly ConcurrentDictionary<Guid, Moderacion> Moderaciones = new();
    public readonly ConcurrentQueue<SobreEventoSaliente> Eventos = new();
    public readonly ConcurrentBag<string> Correlaciones = [];
    public readonly ConcurrentDictionary<Guid, int> FallasTransitorias = new();
    public readonly ConcurrentBag<Guid> RechazosPermanentes = [];
    public readonly HashSet<(Guid, Guid)> Operadores = [];
    public Dictionary<string, Guid[]> Cobertura = new() { ["11001|ENERGIA"] = [EmpresaB, EmpresaA] };
    public int ConflictosPendientes;

    public Task<Caso> AbrirAsync(Caso caso, IdCorrelacion correlacion, CancellationToken ct)
    {
        Correlaciones.Add(correlacion.Valor);
        if (RechazosPermanentes.Contains(caso.IdReporte)) throw new FallaPermanente("rechazado");
        if (FallasTransitorias.TryGetValue(caso.IdReporte, out var n) && n > 0)
        {
            FallasTransitorias[caso.IdReporte] = n - 1;
            throw new HttpRequestException("persistencia caída");
        }
        var existente = Casos.Values.FirstOrDefault(c => c.IdReporte == caso.IdReporte);
        if (existente is not null) return Task.FromResult(existente);
        var nuevo = caso with { Historia = [new EntradaHistoriaCaso(EstadoCaso.RECIBIDO, caso.AbiertoEn, "Caso abierto", null)] };
        Casos[caso.IdCaso] = nuevo;
        return Task.FromResult(nuevo);
    }

    public Task<Caso?> ConsultarAsync(Guid idCaso, IdCorrelacion correlacion, CancellationToken ct) =>
        Task.FromResult(Casos.GetValueOrDefault(idCaso));

    public Task<Caso?> BuscarPorReporteAsync(Guid idReporte, IdCorrelacion correlacion, CancellationToken ct) =>
        Task.FromResult(Casos.Values.FirstOrDefault(c => c.IdReporte == idReporte));

    public Task<Pagina<Caso>> ListarAsync(FiltroCasos f, IdCorrelacion correlacion, CancellationToken ct)
    {
        var lista = Casos.Values
            .Where(c => f.IdEmpresa is null || c.IdEmpresa == f.IdEmpresa)
            .Where(c => !f.SoloSinEmpresa || c.IdEmpresa is null)
            .Where(c => f.Estado is null || c.Estado == f.Estado)
            .Where(c => f.AsignadoA is null || c.AsignadoA == f.AsignadoA)
            .OrderByDescending(c => c.Prioridad).ThenBy(c => c.AbiertoEn).ToList();
        return Task.FromResult(new Pagina<Caso>(lista, lista.Count, f.Pagina, f.Tamano));
    }

    public Task<Caso> ActualizarAsync(Guid idCaso, CambiosCaso c, IReadOnlyList<SobreEventoSaliente> eventos,
        IdCorrelacion correlacion, CancellationToken ct)
    {
        lock (Casos)
        {
            var a = Casos[idCaso];
            if (ConflictosPendientes > 0)
            {
                ConflictosPendientes--;
                Casos[idCaso] = a with { Version = a.Version + 1 };
                throw new ConflictoDeVersion();
            }
            if (a.Version != c.VersionEsperada) throw new ConflictoDeVersion();
            var estado = c.Estado ?? a.Estado;
            var nuevo = a with
            {
                Estado = estado,
                Prioridad = c.Prioridad ?? a.Prioridad,
                PrioridadManual = c.PrioridadManual ?? a.PrioridadManual,
                AsignadoA = c.AsignadoA ?? a.AsignadoA,
                IdEmpresa = c.IdEmpresa ?? a.IdEmpresa,
                CorteValidado = c.CorteValidado ?? a.CorteValidado,
                Resolucion = c.Resolucion ?? a.Resolucion,
                ResueltoEn = c.ResueltoEn ?? a.ResueltoEn,
                CerradoEn = c.CerradoEn ?? a.CerradoEn,
                Version = a.Version + 1,
                Historia = [.. a.Historia, new EntradaHistoriaCaso(estado, c.Fecha, c.Detalle, c.Autor)],
            };
            Casos[idCaso] = nuevo;
            foreach (var e in eventos) Eventos.Enqueue(e);
            return Task.FromResult(nuevo);
        }
    }

    public Task<Archivo?> EvidenciaAsync(Guid idCaso, Guid idEvidencia, IdCorrelacion correlacion, CancellationToken ct) =>
        Task.FromResult<Archivo?>(Casos.ContainsKey(idCaso) ? new Archivo("image/png", [1, 2, 3]) : null);

    public Task<IReadOnlyList<Guid>> EmpresasConCoberturaAsync(string codigoMunicipio, TipoServicio servicio,
        IdCorrelacion correlacion, CancellationToken ct) =>
        Task.FromResult<IReadOnlyList<Guid>>(Cobertura.GetValueOrDefault($"{codigoMunicipio}|{servicio}") ?? []);

    public Task<bool> EsOperadorDeAsync(Guid idCuenta, Guid idEmpresa, IdCorrelacion correlacion, CancellationToken ct) =>
        Task.FromResult(Operadores.Contains((idCuenta, idEmpresa)));

    Task<Moderacion> IRepositorioModeracion.AbrirAsync(Guid idDenuncia, Guid? idEmpresa, DateTimeOffset recibidaEn,
        IdCorrelacion correlacion, CancellationToken ct) =>
        Task.FromResult(Moderaciones.GetOrAdd(idDenuncia, id => new Moderacion(id, TipoServicio.ENERGIA, "11001", "BOGOTÁ",
            4.6, -74.1, "Conexión directa", recibidaEn, idEmpresa, EstadoModeracion.PENDIENTE, null, null, null, 0, [])));

    Task<Moderacion?> IRepositorioModeracion.ConsultarAsync(Guid idDenuncia, IdCorrelacion correlacion, CancellationToken ct) =>
        Task.FromResult(Moderaciones.GetValueOrDefault(idDenuncia));

    Task<Pagina<Moderacion>> IRepositorioModeracion.ListarAsync(EstadoModeracion? estado, Guid? idEmpresa, int pagina,
        int tamano, IdCorrelacion correlacion, CancellationToken ct)
    {
        var lista = Moderaciones.Values.Where(m => idEmpresa is null || m.IdEmpresa == idEmpresa)
            .Where(m => estado is null || m.Estado == estado).ToList();
        return Task.FromResult(new Pagina<Moderacion>(lista, lista.Count, pagina, tamano));
    }

    Task<Moderacion> IRepositorioModeracion.DecidirAsync(Guid idDenuncia, Decision d, IdCorrelacion correlacion,
        CancellationToken ct)
    {
        var m = Moderaciones[idDenuncia];
        if (m.Version != d.VersionEsperada) throw new ConflictoDeVersion();
        var nueva = m with { Estado = d.Estado, Nota = d.Nota, ModeradaPor = d.ModeradaPor, ModeradaEn = d.Fecha, Version = m.Version + 1 };
        Moderaciones[idDenuncia] = nueva;
        return Task.FromResult(nueva);
    }

    Task<Archivo?> IRepositorioModeracion.EvidenciaAsync(Guid idDenuncia, Guid idEvidencia, IdCorrelacion correlacion,
        CancellationToken ct) => Task.FromResult<Archivo?>(null);

    public Caso Sembrar(Guid? empresa = null, EstadoCaso estado = EstadoCaso.RECIBIDO,
        Magnitud magnitud = Magnitud.CUADRA, TipoServicio servicio = TipoServicio.ENERGIA)
    {
        var caso = Caso.AbrirDesdeReporte(Guid.NewGuid(), servicio, "11001", magnitud, DateTimeOffset.UtcNow,
            empresa ?? EmpresaA) with { Estado = estado };
        Casos[caso.IdCaso] = caso;
        return caso;
    }
}
