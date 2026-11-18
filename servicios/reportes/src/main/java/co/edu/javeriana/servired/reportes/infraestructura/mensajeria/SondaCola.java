package co.edu.javeriana.servired.reportes.infraestructura.mensajeria;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.amqp.core.Message;

/**
 * Edad del mensaje más viejo de la cola (ADR-21). Las colas quorum de RabbitMQ no la
 * informan, así que Persistencia publica cada 2 s una sonda (routing key {@code sonda.v1})
 * con su hora de publicación. La cola es FIFO: todo lo publicado antes de la última sonda
 * consumida ya salió, así que el mensaje más viejo pendiente tiene a lo sumo
 * {@code ahora − publicación de esa sonda} (con un margen de 2 s).
 * <p>
 * Antes de la primera sonda se toma la hora de arranque del proceso: un consumidor que no
 * consume nada ve crecer la edad.
 */
public class SondaCola {

    public static final String ROUTING_KEY = "sonda.v1";
    public static final String CABECERA = "x-sonda-publicada-ms";

    private final String cola;
    private final Clock reloj;
    private final AtomicLong ultimaPublicadaMs;
    /** Hasta la primera sonda, la referencia es el arranque; la primera la reemplaza aunque sea más vieja. */
    private volatile boolean recibioSonda;
    private final DistributionSummary espera;

    public SondaCola(String cola, MeterRegistry metricas, Clock reloj) {
        this(cola, metricas, reloj, ManagementFactory.getRuntimeMXBean().getStartTime());
    }

    SondaCola(String cola, MeterRegistry metricas, Clock reloj, long arranqueMs) {
        this.cola = cola;
        this.reloj = reloj;
        this.ultimaPublicadaMs = new AtomicLong(arranqueMs);
        Gauge.builder("servired.cola.mensaje.mas.viejo", this, SondaCola::edadDelMasViejo)
                .tag("cola", cola).baseUnit("segundos")
                .description("Cota superior de la edad del mensaje más viejo de la cola (sonda; ADR-21)")
                .register(metricas);
        this.espera = DistributionSummary.builder("servired.cola.espera.sonda")
                .tag("cola", cola).baseUnit("segundos")
                .description("Tiempo que cada sonda esperó en la cola antes de consumirse")
                .serviceLevelObjectives(0.1, 0.5, 1, 2, 5, 10, 30, 60, 300)
                .register(metricas);
    }

    public static boolean esSonda(Message mensaje) {
        return ROUTING_KEY.equals(mensaje.getMessageProperties().getReceivedRoutingKey());
    }

    /** Registra una sonda consumida; devuelve los segundos que esperó (vacío si no trae la hora). */
    public Optional<Double> recibir(Message mensaje) {
        Object valor = mensaje.getMessageProperties().getHeaders().get(CABECERA);
        if (!(valor instanceof Number publicada)) {
            return Optional.empty();
        }
        if (recibioSonda) {
            ultimaPublicadaMs.accumulateAndGet(publicada.longValue(), Math::max);
        } else {
            // Una sonda publicada antes del arranque (cola atrasada) es la medida real.
            ultimaPublicadaMs.set(publicada.longValue());
            recibioSonda = true;
        }
        double segundos = Math.max(0, (reloj.millis() - publicada.longValue()) / 1000.0);
        espera.record(segundos);
        return Optional.of(segundos);
    }

    double edadDelMasViejo() {
        return Math.max(0, (reloj.millis() - ultimaPublicadaMs.get()) / 1000.0);
    }

    public String cola() {
        return cola;
    }
}
