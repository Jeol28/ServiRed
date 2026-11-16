package co.edu.javeriana.servired.persistencia.aplicacion;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.Outbox;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RegistroIdempotencia;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioFase4;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioReportes;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.UnidadDeTrabajo;
import co.edu.javeriana.servired.persistencia.dominio.Conflicto;
import co.edu.javeriana.servired.persistencia.dominio.EventoSaliente;
import co.edu.javeriana.servired.persistencia.dominio.Fase4.PaginaReportes;
import co.edu.javeriana.servired.persistencia.dominio.Fase4.ReporteCercano;
import co.edu.javeriana.servired.persistencia.dominio.Fase4.Restablecimiento;
import co.edu.javeriana.servired.persistencia.dominio.Fase4.VotoNuevo;
import co.edu.javeriana.servired.persistencia.dominio.IdCorrelacion;
import co.edu.javeriana.servired.persistencia.dominio.OperacionNegocio;
import co.edu.javeriana.servired.persistencia.dominio.RecursoNoEncontrado;
import co.edu.javeriana.servired.persistencia.dominio.ReporteGuardado;
import co.edu.javeriana.servired.persistencia.dominio.ServicioCliente;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Operaciones sobre reportes existentes (RF-2, RF-3, RF-4). Las reglas de
 * negocio (quién, cuándo, cuántos votos validan) las aplica Reportes; aquí se
 * garantiza la atomicidad con el evento y la integridad ante concurrencia.
 */
public class OperacionesReporte {

    private final UnidadDeTrabajo transaccion;
    private final RegistroIdempotencia idempotencia;
    private final RepositorioReportes reportes;
    private final RepositorioFase4 repositorio;
    private final Outbox outbox;

    public OperacionesReporte(UnidadDeTrabajo transaccion, RegistroIdempotencia idempotencia,
                              RepositorioReportes reportes, RepositorioFase4 repositorio, Outbox outbox) {
        this.transaccion = transaccion;
        this.idempotencia = idempotencia;
        this.reportes = reportes;
        this.repositorio = repositorio;
        this.outbox = outbox;
    }

    /** Conflicto ESTADO si el reporte ya estaba restablecido o cerrado. */
    public ReporteGuardado registrarRestablecimiento(ServicioCliente servicio, IdCorrelacion correlacion,
                                                     UUID idReporte, Restablecimiento r, EventoSaliente evento) {
        var operacion = OperacionNegocio.REGISTRAR_RESTABLECIMIENTO;
        operacion.autorizar(servicio);
        evento.validarPara(servicio, correlacion, Set.of("restablecimiento.registrado"));
        return transaccion.ejecutar(() -> {
            if (idempotencia.reclamar(correlacion, operacion, servicio).isPresent()) {
                return cargar(idReporte);
            }
            if (!repositorio.registrarRestablecimiento(idReporte, r)) {
                if (!repositorio.existeReporte(idReporte)) {
                    throw new RecursoNoEncontrado("Reporte inexistente");
                }
                throw new Conflicto("ESTADO", "El reporte ya fue restablecido o cerrado");
            }
            repositorio.agregarHistoria(idReporte, "RESTABLECIDO", r.fechaRestablecimiento(),
                    "Servicio restablecido (duración: " + r.duracionMinutos() + " min)");
            outbox.agregar(evento);
            idempotencia.registrarResultado(correlacion, operacion, idReporte);
            return cargar(idReporte);
        });
    }

    /**
     * Concurrencia optimista: si otro voto entró después de la lectura de Reportes,
     * responde Conflicto VERSION y Reportes recalcula. Un segundo voto del mismo
     * ciudadano responde Conflicto YA_VOTO.
     */
    public ReporteGuardado registrarValidacion(ServicioCliente servicio, IdCorrelacion correlacion, UUID idReporte,
                                               VotoNuevo voto, EventoSaliente evento) {
        var operacion = OperacionNegocio.REGISTRAR_VALIDACION;
        operacion.autorizar(servicio);
        evento.validarPara(servicio, correlacion, Set.of("validacion.registrada"));
        return transaccion.ejecutar(() -> {
            if (idempotencia.reclamar(correlacion, operacion, servicio).isPresent()) {
                return cargar(idReporte);
            }
            if (!repositorio.avanzarVersionValidaciones(idReporte, voto.versionEsperada())) {
                if (!repositorio.existeReporte(idReporte)) {
                    throw new RecursoNoEncontrado("Reporte inexistente");
                }
                throw new Conflicto("VERSION", "Las validaciones cambiaron; vuelva a calcular");
            }
            if (!repositorio.insertarVoto(idReporte, voto)) {
                throw new Conflicto("YA_VOTO", "El ciudadano ya validó este objeto");
            }
            outbox.agregar(evento);
            idempotencia.registrarResultado(correlacion, operacion, idReporte);
            return cargar(idReporte);
        });
    }

    /**
     * Refleja en el reporte un evento de caso (ADR-07). Idempotente por idEvento: una
     * redelivery no duplica la historia. Si el reporte ya está en un estado posterior,
     * no se modifica (nunca retrocede).
     */
    public ReporteGuardado aplicarEstadoDeCaso(ServicioCliente servicio, UUID idReporte,
                                               co.edu.javeriana.servired.persistencia.dominio.Atencion.EstadoPorCaso e) {
        var operacion = OperacionNegocio.APLICAR_ESTADO_DE_CASO;
        operacion.autorizar(servicio);
        var clave = new IdCorrelacion(e.idEvento().toString());
        return transaccion.ejecutar(() -> {
            if (idempotencia.reclamar(clave, operacion, servicio).isEmpty()) {
                if (!repositorio.existeReporte(idReporte)) {
                    throw new RecursoNoEncontrado("Reporte inexistente");
                }
                if (repositorio.cambiarEstado(idReporte, e.estadoNuevo(), e.desde())) {
                    repositorio.agregarHistoria(idReporte, e.estadoNuevo(), e.fecha(), e.detalle());
                }
                idempotencia.registrarResultado(clave, operacion, idReporte);
            }
            return cargar(idReporte);
        });
    }

    public PaginaReportes listarDeCiudadano(ServicioCliente servicio, UUID idCiudadano, int pagina, int tamano) {
        OperacionNegocio.LISTAR_REPORTES_CIUDADANO.autorizar(servicio);
        return repositorio.listarDeCiudadano(idCiudadano, Math.max(0, pagina), Math.clamp(tamano, 1, 100));
    }

    public List<ReporteCercano> cercanos(ServicioCliente servicio, double latitud, double longitud, int radioMetros,
                                         UUID excluirCiudadano, int limite) {
        OperacionNegocio.BUSCAR_REPORTES_CERCANOS.autorizar(servicio);
        return repositorio.cercanos(latitud, longitud, Math.clamp(radioMetros, 1, 5000), excluirCiudadano,
                Math.clamp(limite, 1, 100));
    }

    private ReporteGuardado cargar(UUID id) {
        return reportes.buscar(id).orElseThrow(() -> new RecursoNoEncontrado("Reporte inexistente"));
    }
}
