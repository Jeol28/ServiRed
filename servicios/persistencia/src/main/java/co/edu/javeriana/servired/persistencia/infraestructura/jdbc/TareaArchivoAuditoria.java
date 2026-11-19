package co.edu.javeriana.servired.persistencia.infraestructura.jdbc;

import co.edu.javeriana.servired.persistencia.aplicacion.ArchivoAuditoria;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Archiva cada día los meses del registro de auditoría más viejos que la ventana en la base
 * (servired.auditoria.archivo.*, ADR-29). Una falla solo se registra: se reintenta al día
 * siguiente, y mientras tanto los registros siguen en la base.
 */
@Component
@ConditionalOnProperty(name = "servired.auditoria.archivo.activo", havingValue = "true", matchIfMissing = true)
public class TareaArchivoAuditoria {

    private static final Logger log = LoggerFactory.getLogger(TareaArchivoAuditoria.class);

    private final ArchivoAuditoria archivo;
    private final Counter archivados;
    private final Counter detenidos;
    private final Counter fallas;

    public TareaArchivoAuditoria(ArchivoAuditoria archivo, MeterRegistry metricas) {
        this.archivo = archivo;
        this.archivados = Counter.builder("servired.auditoria.registros.archivados")
                .description("Registros de auditoría movidos de la base a segmentos archivados").register(metricas);
        this.detenidos = Counter.builder("servired.auditoria.archivo.detenidos")
                .description("Archivados detenidos porque la cadena de huellas estaba rota").register(metricas);
        this.fallas = Counter.builder("servired.auditoria.archivo.fallas")
                .description("Archivados que no pudieron completarse (almacén o base sin responder)").register(metricas);
    }

    @Scheduled(initialDelayString = "${servired.auditoria.archivo.retraso-inicial:PT20M}",
               fixedDelayString = "${servired.auditoria.archivo.intervalo:P1D}")
    public void ejecutar() {
        try {
            var r = archivo.archivar(archivo.limitePorDefecto());
            archivados.increment(r.registrosArchivados());
            if (r.detenidoPor() != null) {
                detenidos.increment();
            }
            log.atInfo().addKeyValue("segmentos", r.segmentosNuevos()).addKeyValue("registros", r.registrosArchivados())
                    .addKeyValue("limite", r.limite()).addKeyValue("detenidoPor", r.detenidoPor())
                    .log("archivado de auditoría");
        } catch (RuntimeException e) {
            fallas.increment();
            log.warn("el archivado de auditoría no pudo completarse; se reintenta en la siguiente ejecución: {}",
                    e.getMessage());
        }
    }
}
