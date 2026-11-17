using ServiRed.Atencion.Aplicacion.Puertos;
using ServiRed.Atencion.Dominio;

namespace ServiRed.Atencion.Aplicacion;

/// <summary>Moderación de denuncias anónimas (RF-5): someterlas a validación con su contenido y evidencia.</summary>
public sealed class GestionModeracion(IRepositorioModeracion moderaciones, TimeProvider reloj)
{
    public Task<Pagina<Moderacion>> ListarAsync(Actor actor, EstadoModeracion? estado, Guid? idEmpresa, int pagina,
        int tamano, IdCorrelacion correlacion, CancellationToken ct)
    {
        if (actor.Rol is not (Rol.OPERADOR or Rol.REGULADOR or Rol.ADMINISTRADOR))
            throw new AccesoDenegado("Rol sin acceso a la moderación");
        return moderaciones.ListarAsync(estado, actor.EmpresaForzada ?? idEmpresa, pagina, Math.Clamp(tamano, 1, 100),
            correlacion, ct);
    }

    public async Task<Moderacion> ConsultarAsync(Actor actor, Guid idDenuncia, IdCorrelacion correlacion,
        CancellationToken ct)
    {
        var m = await moderaciones.ConsultarAsync(idDenuncia, correlacion, ct);
        if (m is null || !actor.PuedeVer(m.IdEmpresa)) throw new NoEncontrado("La denuncia no existe");
        return m;
    }

    public async Task<Moderacion> DecidirAsync(Actor actor, Guid idDenuncia, EstadoModeracion decision, string? nota,
        IdCorrelacion correlacion, CancellationToken ct)
    {
        var m = await ConsultarAsync(actor, idDenuncia, correlacion, ct);
        var ahora = reloj.GetUtcNow();
        var d = m.Decidir(actor, decision, nota, ahora);
        return await moderaciones.DecidirAsync(idDenuncia, d,
            [EventosCaso.DenunciaModerada(actor, idDenuncia, d.Estado, correlacion, ahora)], correlacion, ct);
    }

    public async Task<Archivo> EvidenciaAsync(Actor actor, Guid idDenuncia, Guid idEvidencia, IdCorrelacion correlacion,
        CancellationToken ct)
    {
        await ConsultarAsync(actor, idDenuncia, correlacion, ct);
        return await moderaciones.EvidenciaAsync(idDenuncia, idEvidencia, correlacion, ct)
               ?? throw new NoEncontrado("La evidencia no existe");
    }
}
