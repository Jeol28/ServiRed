using ServiRed.Atencion.Aplicacion.Puertos;
using ServiRed.Atencion.Dominio;

namespace ServiRed.Atencion.Aplicacion;

/// <summary>
/// Panel del operador (RF-7): filtrar, priorizar, asignar, actualizar y cerrar.
/// El operador solo ve y gestiona los casos de su empresa; el regulador consulta
/// todo; el administrador además enruta. Cada cambio publica sus eventos.
/// </summary>
public sealed class GestionCasos(IRepositorioCasos casos, ICatalogoAtencion catalogo, TimeProvider reloj)
{
    public Task<Pagina<Caso>> ListarAsync(Actor actor, FiltroCasos filtro, IdCorrelacion correlacion, CancellationToken ct)
    {
        if (actor.Rol is not (Rol.OPERADOR or Rol.REGULADOR or Rol.ADMINISTRADOR))
            throw new AccesoDenegado("Rol sin acceso a casos");
        var efectivo = actor.EmpresaForzada is { } propia ? filtro with { IdEmpresa = propia, SoloSinEmpresa = false } : filtro;
        return casos.ListarAsync(efectivo with { Tamano = Math.Clamp(filtro.Tamano, 1, 100) }, correlacion, ct);
    }

    public async Task<Caso> ConsultarAsync(Actor actor, Guid idCaso, IdCorrelacion correlacion, CancellationToken ct)
    {
        var caso = await casos.ConsultarAsync(idCaso, correlacion, ct);
        // Un caso de otra empresa responde 404: no se revela que existe.
        if (caso is null || !actor.PuedeVer(caso.IdEmpresa)) throw new NoEncontrado("El caso no existe");
        return caso;
    }

    public Task<Caso> AtenderAsync(Actor actor, Guid idCaso, IdCorrelacion correlacion, CancellationToken ct) =>
        DecidirAsync(actor, idCaso, c => c.Atender(actor, reloj.GetUtcNow()), correlacion, ct);

    public Task<Caso> ResolverAsync(Actor actor, Guid idCaso, string? resolucion, IdCorrelacion correlacion,
        CancellationToken ct) =>
        DecidirAsync(actor, idCaso, c => c.Resolver(actor, resolucion, reloj.GetUtcNow()), correlacion, ct);

    public Task<Caso> CerrarAsync(Actor actor, Guid idCaso, string? motivo, IdCorrelacion correlacion, CancellationToken ct) =>
        DecidirAsync(actor, idCaso, c => c.Cerrar(actor, motivo, reloj.GetUtcNow()), correlacion, ct);

    public Task<Caso> FijarPrioridadAsync(Actor actor, Guid idCaso, Prioridad prioridad, IdCorrelacion correlacion,
        CancellationToken ct) =>
        DecidirAsync(actor, idCaso, c => c.FijarPrioridad(actor, prioridad, reloj.GetUtcNow()), correlacion, ct);

    /// <summary>El administrador enruta, solo a la empresa dueña de la zona del caso (ADR-35).</summary>
    public async Task<Caso> EnrutarAsync(Actor actor, Guid idCaso, Guid idEmpresa, IdCorrelacion correlacion,
        CancellationToken ct)
    {
        var caso = await ConsultarAsync(actor, idCaso, correlacion, ct);
        var zona = await catalogo.CoberturaAsync(ConsultaCobertura.DeCaso(caso), correlacion, ct);
        return await GuardarAsync(caso, caso.Enrutar(actor, idEmpresa, zona, reloj.GetUtcNow()), correlacion, ct);
    }

    /// <summary>La zona del caso y su empresa dueña: para enrutar desde el portal.</summary>
    public async Task<Cobertura> ZonaAsync(Actor actor, Guid idCaso, IdCorrelacion correlacion, CancellationToken ct)
    {
        var caso = await ConsultarAsync(actor, idCaso, correlacion, ct);
        return await catalogo.CoberturaAsync(ConsultaCobertura.DeCaso(caso), correlacion, ct)
               ?? throw new NoEncontrado("No se pudo ubicar la zona del caso");
    }

    /// <summary>Catálogo de empresas prestadoras reales (nombres en el portal y alta de operadores).</summary>
    public Task<IReadOnlyList<EmpresaPrestadora>> EmpresasAsync(Actor actor, FiltroEmpresas filtro,
        IdCorrelacion correlacion, CancellationToken ct)
    {
        if (actor.Rol is not (Rol.OPERADOR or Rol.REGULADOR or Rol.ADMINISTRADOR))
            throw new AccesoDenegado("Rol sin acceso al catálogo de empresas");
        return catalogo.EmpresasAsync(filtro, correlacion, ct);
    }

    /// <summary>Asigna a otro operador de la empresa del caso o, si no se indica, a quien lo pide.</summary>
    public async Task<Caso> AsignarAsync(Actor actor, Guid idCaso, Guid? idOperador, IdCorrelacion correlacion,
        CancellationToken ct)
    {
        var caso = await ConsultarAsync(actor, idCaso, correlacion, ct);
        var destino = idOperador ?? actor.IdCuenta;
        if (caso.IdEmpresa is { } empresa && !await catalogo.EsOperadorDeAsync(destino, empresa, correlacion, ct))
            throw new ReglaIncumplida("OPERADOR_INVALIDO", "La cuenta no es un operador activo de la empresa del caso");
        return await GuardarAsync(caso, caso.Asignar(actor, destino, reloj.GetUtcNow()), correlacion, ct);
    }

    public async Task<Archivo> EvidenciaAsync(Actor actor, Guid idCaso, Guid idEvidencia, IdCorrelacion correlacion,
        CancellationToken ct)
    {
        await ConsultarAsync(actor, idCaso, correlacion, ct);
        return await casos.EvidenciaAsync(idCaso, idEvidencia, correlacion, ct)
               ?? throw new NoEncontrado("La evidencia no existe");
    }

    private async Task<Caso> DecidirAsync(Actor actor, Guid idCaso, Func<Caso, CambiosCaso> decidir,
        IdCorrelacion correlacion, CancellationToken ct)
    {
        var caso = await ConsultarAsync(actor, idCaso, correlacion, ct);
        return await GuardarAsync(caso, decidir(caso), correlacion, ct);
    }

    private Task<Caso> GuardarAsync(Caso caso, CambiosCaso cambios, IdCorrelacion correlacion, CancellationToken ct) =>
        casos.ActualizarAsync(caso.IdCaso, cambios, EventosCaso.Para(caso, cambios, correlacion), correlacion, ct);
}
