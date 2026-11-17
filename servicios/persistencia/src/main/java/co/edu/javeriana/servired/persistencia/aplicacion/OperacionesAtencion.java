package co.edu.javeriana.servired.persistencia.aplicacion;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.AlmacenEvidencias;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.Outbox;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RegistroIdempotencia;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioAtencion;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.UnidadDeTrabajo;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.CambiosCaso;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.Decision;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.Empresa;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.FiltroCasos;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.Moderacion;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.ModeracionNueva;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.PaginaCasos;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.PaginaModeraciones;
import co.edu.javeriana.servired.persistencia.dominio.CasoGuardado;
import co.edu.javeriana.servired.persistencia.dominio.Conflicto;
import co.edu.javeriana.servired.persistencia.dominio.EventoSaliente;
import co.edu.javeriana.servired.persistencia.dominio.Fase4.Archivo;
import co.edu.javeriana.servired.persistencia.dominio.IdCorrelacion;
import co.edu.javeriana.servired.persistencia.dominio.OperacionNegocio;
import co.edu.javeriana.servired.persistencia.dominio.RecursoNoEncontrado;
import co.edu.javeriana.servired.persistencia.dominio.ServicioCliente;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Operaciones del dominio de Atención (RF-6, RF-7, moderación de RF-5). Las
 * reglas (transiciones, prioridad, enrutamiento, quién puede qué) las decide
 * Atención; aquí se garantiza la atomicidad con el evento y la concurrencia.
 */
public class OperacionesAtencion {

    private final UnidadDeTrabajo transaccion;
    private final RegistroIdempotencia idempotencia;
    private final RepositorioAtencion repositorio;
    private final AlmacenEvidencias almacen;
    private final Outbox outbox;

    public OperacionesAtencion(UnidadDeTrabajo transaccion, RegistroIdempotencia idempotencia,
                               RepositorioAtencion repositorio, AlmacenEvidencias almacen, Outbox outbox) {
        this.transaccion = transaccion;
        this.idempotencia = idempotencia;
        this.repositorio = repositorio;
        this.almacen = almacen;
        this.outbox = outbox;
    }

    public List<Empresa> cobertura(ServicioCliente s, String codigoMunicipio, String tipoServicio) {
        OperacionNegocio.BUSCAR_COBERTURA.autorizar(s);
        return repositorio.empresasConCobertura(codigoMunicipio, tipoServicio);
    }

    public Optional<CasoGuardado> caso(ServicioCliente s, UUID idCaso) {
        OperacionNegocio.CONSULTAR_CASO.autorizar(s);
        return repositorio.caso(idCaso);
    }

    public PaginaCasos listar(ServicioCliente s, FiltroCasos filtro) {
        OperacionNegocio.LISTAR_CASOS.autorizar(s);
        return repositorio.listar(filtro);
    }

    /**
     * Aplica los cambios con concurrencia optimista (409 VERSION), agrega la
     * entrada de historia y deja los eventos en el outbox, todo en una transacción.
     */
    public CasoGuardado actualizar(ServicioCliente s, IdCorrelacion correlacion, UUID idCaso, CambiosCaso cambios,
                                   List<EventoSaliente> eventos) {
        var operacion = OperacionNegocio.ACTUALIZAR_CASO;
        operacion.autorizar(s);
        eventos.forEach(e -> e.validarPara(s, correlacion, Set.of("caso.actualizado", "caso.cerrado")));
        return transaccion.ejecutar(() -> {
            if (idempotencia.reclamar(correlacion, operacion, s).isPresent()) {
                return cargar(idCaso);
            }
            if (!repositorio.actualizar(idCaso, cambios)) {
                if (!repositorio.existeCaso(idCaso)) {
                    throw new RecursoNoEncontrado("Caso inexistente");
                }
                throw new Conflicto("VERSION", "El caso cambió; vuelva a consultarlo");
            }
            if (cambios.detalleHistoria() != null) {
                var actual = cargar(idCaso);
                repositorio.agregarHistoria(idCaso, actual.estado(), cambios.fecha(), cambios.detalleHistoria(),
                        cambios.autor());
            }
            eventos.forEach(outbox::agregar);
            idempotencia.registrarResultado(correlacion, operacion, idCaso);
            return cargar(idCaso);
        });
    }

    public boolean esOperadorDe(ServicioCliente s, UUID idCuenta, UUID idEmpresa) {
        OperacionNegocio.VERIFICAR_OPERADOR.autorizar(s);
        return repositorio.esOperadorDe(idCuenta, idEmpresa);
    }

    public Moderacion abrirModeracion(ServicioCliente s, ModeracionNueva m) {
        OperacionNegocio.ABRIR_MODERACION.autorizar(s);
        return transaccion.ejecutar(() -> {
            repositorio.abrirModeracionSiNoExiste(m);
            return repositorio.moderacion(m.idDenuncia())
                    .orElseThrow(() -> new RecursoNoEncontrado("Denuncia inexistente"));
        });
    }

    public Optional<Moderacion> moderacion(ServicioCliente s, UUID idDenuncia) {
        OperacionNegocio.CONSULTAR_MODERACION.autorizar(s);
        return repositorio.moderacion(idDenuncia);
    }

    public PaginaModeraciones listarModeraciones(ServicioCliente s, String estado, UUID idEmpresa, int pagina,
                                                 int tamano) {
        OperacionNegocio.CONSULTAR_MODERACION.autorizar(s);
        return repositorio.listarModeraciones(estado, idEmpresa, Math.max(0, pagina), Math.clamp(tamano, 1, 100));
    }

    public Moderacion decidir(ServicioCliente s, UUID idDenuncia, Decision d) {
        return decidir(s, IdCorrelacion.nuevo(), idDenuncia, d, List.of());
    }

    /** Con accion.auditada (RF-12) en la misma transacción de la decisión. */
    public Moderacion decidir(ServicioCliente s, IdCorrelacion correlacion, UUID idDenuncia, Decision d,
                              List<EventoSaliente> eventos) {
        OperacionNegocio.DECIDIR_MODERACION.autorizar(s);
        eventos.forEach(e -> e.validarPara(s, correlacion, Set.of("accion.auditada")));
        return transaccion.ejecutar(() -> {
            if (!repositorio.decidir(idDenuncia, d)) {
                if (repositorio.moderacion(idDenuncia).isEmpty()) {
                    throw new RecursoNoEncontrado("Denuncia inexistente");
                }
                throw new Conflicto("VERSION", "La moderación cambió; vuelva a consultarla");
            }
            eventos.forEach(outbox::agregar);
            return repositorio.moderacion(idDenuncia).orElseThrow();
        });
    }

    /** Evidencia del reporte asociado al caso; Atención nunca accede a reportes por su id. */
    public Optional<Archivo> evidenciaDeCaso(ServicioCliente s, UUID idCaso, UUID idEvidencia) {
        OperacionNegocio.OBTENER_EVIDENCIA_CASO.autorizar(s);
        return repositorio.caso(idCaso)
                .flatMap(c -> repositorio.evidenciaDeReporte(c.idReporte(), idEvidencia))
                .flatMap(ref -> almacen.leer(ref.clave()).map(b -> new Archivo(ref.tipoContenido(), b)));
    }

    public Optional<Archivo> evidenciaDeDenuncia(ServicioCliente s, UUID idDenuncia, UUID idEvidencia) {
        OperacionNegocio.OBTENER_EVIDENCIA_DENUNCIA.autorizar(s);
        return repositorio.evidenciaDeDenuncia(idDenuncia, idEvidencia)
                .flatMap(ref -> almacen.leer(ref.clave()).map(b -> new Archivo(ref.tipoContenido(), b)));
    }

    private CasoGuardado cargar(UUID idCaso) {
        return repositorio.caso(idCaso).orElseThrow(() -> new RecursoNoEncontrado("Caso inexistente"));
    }
}
