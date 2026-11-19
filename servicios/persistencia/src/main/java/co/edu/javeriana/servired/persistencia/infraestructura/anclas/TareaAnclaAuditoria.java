package co.edu.javeriana.servired.persistencia.infraestructura.anclas;

import co.edu.javeriana.servired.persistencia.aplicacion.AnclasAuditoria;
import co.edu.javeriana.servired.persistencia.aplicacion.AnclasAuditoria.CadenaRota;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fija y publica el ancla del día (ADR-31). Corre cada pocas horas: el ancla es una por día, así
 * que las siguientes ejecuciones solo reintentan la publicación si falló.
 */
@Component
@ConditionalOnProperty(name = "servired.auditoria.anclas.activo", havingValue = "true", matchIfMissing = true)
public class TareaAnclaAuditoria {

    private static final Logger log = LoggerFactory.getLogger(TareaAnclaAuditoria.class);

    private final AnclasAuditoria anclas;
    private final Counter fallas;
    private final Counter cadenaRota;
    private final Instant inicio = Instant.now();
    private volatile Instant ultimaPublicada;

    public TareaAnclaAuditoria(AnclasAuditoria anclas, MeterRegistry metricas) {
        this.anclas = anclas;
        this.fallas = Counter.builder("servired.auditoria.anclas.fallas")
                .description("Intentos de anclar o publicar que fallaron (GitHub o la base sin responder)")
                .register(metricas);
        this.cadenaRota = Counter.builder("servired.auditoria.anclas.cadena.rota")
                .description("Anclas no fijadas porque la cadena interna está rota").register(metricas);
        Gauge.builder("servired.auditoria.ancla.antiguedad", this, TareaAnclaAuditoria::antiguedad).baseUnit("segundos")
                .description("Segundos desde la última ancla publicada afuera (o desde el arranque, si ninguna)")
                .register(metricas);
    }

    @Scheduled(initialDelayString = "${servired.auditoria.anclas.retraso-inicial:PT30M}",
               fixedDelayString = "${servired.auditoria.anclas.intervalo:PT6H}")
    public void ejecutar() {
        try {
            var ancla = anclas.anclar();
            ancla.filter(a -> a.publicadaEn() != null).ifPresent(a -> ultimaPublicada = a.publicadaEn());
            if (!anclas.publicacionConfigurada()) {
                log.warn("sin destino externo para las anclas de auditoría (AUDITORIA_ANCLAS_GITHUB_*): "
                        + "solo quedan en la base y en la API pública");
            }
        } catch (CadenaRota e) {
            cadenaRota.increment();
            log.error(e.getMessage());
        } catch (RuntimeException e) {
            fallas.increment();
            log.warn("no se pudo anclar o publicar el ancla de auditoría; se reintenta: {}", e.getMessage());
        }
    }

    private double antiguedad() {
        Instant desde = ultimaPublicada == null ? inicio : ultimaPublicada;
        return Duration.between(desde, Instant.now()).toSeconds();
    }
}
