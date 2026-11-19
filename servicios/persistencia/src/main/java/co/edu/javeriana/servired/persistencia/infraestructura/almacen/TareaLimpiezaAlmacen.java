package co.edu.javeriana.servired.persistencia.infraestructura.almacen;

import co.edu.javeriana.servired.persistencia.aplicacion.LimpiezaAlmacen;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ejecuta periódicamente la limpieza de objetos huérfanos del almacén
 * (servired.almacen.limpieza.*). Una falla (almacén o base caídos) solo se registra:
 * se reintenta en la siguiente ejecución.
 */
@Component
@ConditionalOnProperty(name = "servired.almacen.limpieza.activa", havingValue = "true", matchIfMissing = true)
public class TareaLimpiezaAlmacen {

    private static final Logger log = LoggerFactory.getLogger(TareaLimpiezaAlmacen.class);

    private final LimpiezaAlmacen limpieza;
    private final Counter borrados;
    private final Counter frenadas;
    private final Counter fallas;

    public TareaLimpiezaAlmacen(LimpiezaAlmacen limpieza, MeterRegistry metricas) {
        this.limpieza = limpieza;
        this.borrados = Counter.builder("servired.almacen.huerfanos.borrados")
                .description("Objetos del almacén sin referencia en la base que se borraron").register(metricas);
        this.frenadas = Counter.builder("servired.almacen.limpieza.frenadas")
                .description("Limpiezas que no borraron nada por exceder el freno (posible configuración cruzada)")
                .register(metricas);
        this.fallas = Counter.builder("servired.almacen.limpieza.fallas")
                .description("Limpiezas que no pudieron completarse (almacén o base sin responder)").register(metricas);
    }

    @Scheduled(initialDelayString = "${servired.almacen.limpieza.retraso-inicial:PT10M}",
               fixedDelayString = "${servired.almacen.limpieza.intervalo:PT6H}")
    public void ejecutar() {
        try {
            LimpiezaAlmacen.Resultado r = limpieza.ejecutar();
            borrados.increment(r.borrados());
            if (r.frenada()) {
                frenadas.increment();
            }
            log.atInfo().addKeyValue("revisados", r.revisados()).addKeyValue("recientes", r.recientes())
                    .addKeyValue("huerfanos", r.huerfanos()).addKeyValue("borrados", r.borrados())
                    .addKeyValue("fallidos", r.fallidos()).addKeyValue("frenada", r.frenada())
                    .log("limpieza del almacén de evidencias");
        } catch (RuntimeException e) {
            fallas.increment();
            log.warn("la limpieza del almacén no pudo completarse; se reintenta en la siguiente ejecución: {}",
                    e.getMessage());
        }
    }
}
