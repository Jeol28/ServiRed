package co.edu.javeriana.servired.persistencia.aplicacion;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.CatalogoTerritorial;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.Outbox;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RegistroIdempotencia;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioReportes;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.UnidadDeTrabajo;
import co.edu.javeriana.servired.persistencia.dominio.EventoSaliente;
import co.edu.javeriana.servired.persistencia.dominio.IdCorrelacion;
import co.edu.javeriana.servired.persistencia.dominio.OperacionNegocio;
import co.edu.javeriana.servired.persistencia.dominio.ReporteGuardado;
import co.edu.javeriana.servired.persistencia.dominio.ReporteNuevo;
import co.edu.javeriana.servired.persistencia.dominio.ServicioCliente;
import co.edu.javeriana.servired.persistencia.dominio.SolicitudRechazada;
import java.util.Set;
import java.util.UUID;

/**
 * guardarReporte: en UNA transacción guarda el reporte, su primera entrada de
 * historia y el evento reporte.creado en el outbox. Un reintento con el mismo
 * X-Correlation-Id devuelve el reporte original sin escribir de nuevo.
 */
public class GuardarReporte {

    private static final OperacionNegocio OPERACION = OperacionNegocio.GUARDAR_REPORTE;

    private final UnidadDeTrabajo transaccion;
    private final RegistroIdempotencia idempotencia;
    private final RepositorioReportes reportes;
    private final CatalogoTerritorial catalogo;
    private final Outbox outbox;

    public GuardarReporte(UnidadDeTrabajo transaccion, RegistroIdempotencia idempotencia,
                          RepositorioReportes reportes, CatalogoTerritorial catalogo, Outbox outbox) {
        this.transaccion = transaccion;
        this.idempotencia = idempotencia;
        this.reportes = reportes;
        this.catalogo = catalogo;
        this.outbox = outbox;
    }

    public Resultado ejecutar(ServicioCliente servicio, IdCorrelacion correlacion,
                              ReporteNuevo reporte, EventoSaliente evento) {
        OPERACION.autorizar(servicio);
        evento.validarPara(servicio, correlacion, Set.of("reporte.creado"));

        return transaccion.ejecutar(() -> {
            var previo = idempotencia.reclamar(correlacion, OPERACION, servicio);
            if (previo.isPresent()) {
                return new Resultado(cargar(previo.get()), false);
            }
            if (!catalogo.existeMunicipio(reporte.codigoMunicipio())) {
                throw new SolicitudRechazada("Municipio inexistente: " + reporte.codigoMunicipio());
            }
            reportes.insertar(reporte);
            outbox.agregar(evento);
            idempotencia.registrarResultado(correlacion, OPERACION, reporte.idReporte());
            return new Resultado(cargar(reporte.idReporte()), true);
        });
    }

    private ReporteGuardado cargar(UUID idReporte) {
        return reportes.buscar(idReporte)
                .orElseThrow(() -> new IllegalStateException("Reporte no encontrado tras guardarlo: " + idReporte));
    }

    /** @param nuevo false si la solicitud era un reintento ya procesado */
    public record Resultado(ReporteGuardado reporte, boolean nuevo) {
    }
}
