using ServiRed.Atencion.Api.Correlacion;
using ServiRed.Atencion.Api.Seguridad;
using ServiRed.Atencion.Aplicacion;
using ServiRed.Atencion.Aplicacion.Puertos;
using ServiRed.Atencion.Dominio;

namespace ServiRed.Atencion.Api.Endpoints;

/// <summary>
/// API del operador (RF-6, RF-7) y de moderación de denuncias (RF-5), detrás del
/// gateway. Leer: operador, regulador, administrador. Gestionar: operador y
/// administrador. El operador solo ve los casos de su empresa.
/// </summary>
public static class EndpointsAtencion
{
    public sealed record ResolucionDto(string? Resolucion);
    public sealed record AsignacionDto(Guid? IdOperador);
    public sealed record PrioridadDto(Prioridad Prioridad);
    public sealed record EmpresaDto(Guid IdEmpresa);
    public sealed record DecisionDto(EstadoModeracion Decision, string? Nota);

    public static void MapearAtencion(this WebApplication app)
    {
        var casos = app.MapGroup("/api/v1/casos").RequireAuthorization(ConfiguracionJwt.PoliticaLeerCasos);

        casos.MapGet("", (HttpContext h, GestionCasos g, CancellationToken ct, EstadoCaso? estado,
                TipoServicio? tipoServicio, string? codigoMunicipio, Prioridad? prioridad, Guid? idEmpresa,
                bool? sinEmpresa, Guid? asignadoA, bool? mios, int? pagina, int? tamano, string? codigoDepartamento) =>
            {
                var actor = ActorActual.De(h);
                return g.ListarAsync(actor, new FiltroCasos(estado, tipoServicio, codigoMunicipio, prioridad, idEmpresa,
                    sinEmpresa ?? false, mios == true ? actor.IdCuenta : asignadoA, null, pagina ?? 0, tamano ?? 20,
                    codigoDepartamento),
                    Correlacion(h), ct);
            });

        casos.MapGet("/{idCaso:guid}", (Guid idCaso, HttpContext h, GestionCasos g, CancellationToken ct) =>
            g.ConsultarAsync(ActorActual.De(h), idCaso, Correlacion(h), ct));

        // Zona del caso y su única empresa dueña (ADR-35): el portal la ofrece al enrutar.
        casos.MapGet("/{idCaso:guid}/zona", (Guid idCaso, HttpContext h, GestionCasos g, CancellationToken ct) =>
            g.ZonaAsync(ActorActual.De(h), idCaso, Correlacion(h), ct));

        casos.MapGet("/{idCaso:guid}/evidencias/{idEvidencia:guid}",
            async (Guid idCaso, Guid idEvidencia, HttpContext h, GestionCasos g, CancellationToken ct) =>
            {
                var a = await g.EvidenciaAsync(ActorActual.De(h), idCaso, idEvidencia, Correlacion(h), ct);
                h.Response.Headers.CacheControl = "no-store";
                h.Response.Headers["X-Content-Type-Options"] = "nosniff";
                return Results.File(a.Contenido, a.TipoContenido);
            });

        var gestion = casos.MapGroup("").RequireAuthorization(ConfiguracionJwt.PoliticaGestionarCasos);

        gestion.MapPost("/{idCaso:guid}/atender", (Guid idCaso, HttpContext h, GestionCasos g, CancellationToken ct) =>
            g.AtenderAsync(ActorActual.De(h), idCaso, Correlacion(h), ct));

        gestion.MapPost("/{idCaso:guid}/resolver",
            (Guid idCaso, ResolucionDto dto, HttpContext h, GestionCasos g, CancellationToken ct) =>
                g.ResolverAsync(ActorActual.De(h), idCaso, dto.Resolucion, Correlacion(h), ct));

        gestion.MapPost("/{idCaso:guid}/cerrar",
            (Guid idCaso, ResolucionDto dto, HttpContext h, GestionCasos g, CancellationToken ct) =>
                g.CerrarAsync(ActorActual.De(h), idCaso, dto.Resolucion, Correlacion(h), ct));

        gestion.MapPut("/{idCaso:guid}/asignacion",
            (Guid idCaso, AsignacionDto dto, HttpContext h, GestionCasos g, CancellationToken ct) =>
                g.AsignarAsync(ActorActual.De(h), idCaso, dto.IdOperador, Correlacion(h), ct));

        gestion.MapPut("/{idCaso:guid}/prioridad",
            (Guid idCaso, PrioridadDto dto, HttpContext h, GestionCasos g, CancellationToken ct) =>
                g.FijarPrioridadAsync(ActorActual.De(h), idCaso, dto.Prioridad, Correlacion(h), ct));

        gestion.MapPut("/{idCaso:guid}/empresa",
            (Guid idCaso, EmpresaDto dto, HttpContext h, GestionCasos g, CancellationToken ct) =>
                g.EnrutarAsync(ActorActual.De(h), idCaso, dto.IdEmpresa, Correlacion(h), ct));

        // Catálogo de empresas prestadoras reales (ADR-35).
        app.MapGet("/api/v1/empresas", (HttpContext h, GestionCasos g, CancellationToken ct, TipoServicio? tipoServicio,
                string? codigoDepartamento, string? codigoMunicipio) =>
                g.EmpresasAsync(ActorActual.De(h),
                    new FiltroEmpresas(tipoServicio, Vacio(codigoDepartamento), Vacio(codigoMunicipio)), Correlacion(h), ct))
            .RequireAuthorization(ConfiguracionJwt.PoliticaLeerCasos);

        // --------------------------------------------------------- moderación
        var moderacion = app.MapGroup("/api/v1/moderacion/denuncias")
            .RequireAuthorization(ConfiguracionJwt.PoliticaLeerCasos);

        moderacion.MapGet("", (HttpContext h, GestionModeracion g, CancellationToken ct, EstadoModeracion? estado,
                Guid? idEmpresa, int? pagina, int? tamano) =>
            g.ListarAsync(ActorActual.De(h), estado, idEmpresa, pagina ?? 0, tamano ?? 20, Correlacion(h), ct));

        moderacion.MapGet("/{idDenuncia:guid}", (Guid idDenuncia, HttpContext h, GestionModeracion g, CancellationToken ct) =>
            g.ConsultarAsync(ActorActual.De(h), idDenuncia, Correlacion(h), ct));

        moderacion.MapGet("/{idDenuncia:guid}/evidencias/{idEvidencia:guid}",
            async (Guid idDenuncia, Guid idEvidencia, HttpContext h, GestionModeracion g, CancellationToken ct) =>
            {
                var a = await g.EvidenciaAsync(ActorActual.De(h), idDenuncia, idEvidencia, Correlacion(h), ct);
                h.Response.Headers.CacheControl = "no-store";
                h.Response.Headers["X-Content-Type-Options"] = "nosniff";
                return Results.File(a.Contenido, a.TipoContenido);
            });

        moderacion.MapPost("/{idDenuncia:guid}/decision",
                (Guid idDenuncia, DecisionDto dto, HttpContext h, GestionModeracion g, CancellationToken ct) =>
                    g.DecidirAsync(ActorActual.De(h), idDenuncia, dto.Decision, dto.Nota, Correlacion(h), ct))
            .RequireAuthorization(ConfiguracionJwt.PoliticaGestionarCasos);
    }

    private static string? Vacio(string? v) => string.IsNullOrWhiteSpace(v) ? null : v.Trim();

    private static IdCorrelacion Correlacion(HttpContext h) =>
        (IdCorrelacion)h.Items[MiddlewareCorrelacion.ClaveItem]!;
}
