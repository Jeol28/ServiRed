package co.edu.javeriana.servired.persistencia.infraestructura.mensajeria;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publica cada 2 s un mensaje sonda hacia todas las colas consumidoras (ADR-21). Las
 * colas quorum de RabbitMQ no informan la edad de su mensaje más viejo; con la sonda,
 * cada consumidor la acota: todo lo publicado antes de la última sonda que consumió ya
 * salió de la cola (FIFO).
 * <p>
 * {@code servired_sonda_ultima_publicacion_segundos} dice hace cuánto se publicó la
 * última: si quien publica está caído, las alertas de antigüedad no culpan a las colas.
 */
@Component
public class SondaColas {

    private static final Logger log = LoggerFactory.getLogger(SondaColas.class);

    private final PublicadorConfirmado publicador;
    private final Clock reloj;
    private final AtomicLong ultimaMs;

    @Autowired
    public SondaColas(PublicadorConfirmado publicador, MeterRegistry metricas) {
        this(publicador, metricas, Clock.systemUTC());
    }

    SondaColas(PublicadorConfirmado publicador, MeterRegistry metricas, Clock reloj) {
        this.publicador = publicador;
        this.reloj = reloj;
        this.ultimaMs = new AtomicLong(reloj.millis());
        Gauge.builder("servired.sonda.ultima.publicacion", this, SondaColas::segundosDesdeLaUltima)
                .baseUnit("segundos")
                .description("Segundos desde la última sonda publicada hacia las colas")
                .register(metricas);
    }

    @Scheduled(fixedRateString = "${servired.sonda.intervalo-ms:2000}")
    public void publicar() {
        long ahora = reloj.millis();
        try {
            publicador.publicarSonda(ahora);
            ultimaMs.set(ahora);
        } catch (RuntimeException e) {
            log.warn("no se pudo publicar la sonda de colas: {}", e.getMessage());
        }
    }

    double segundosDesdeLaUltima() {
        return (reloj.millis() - ultimaMs.get()) / 1000.0;
    }
}
