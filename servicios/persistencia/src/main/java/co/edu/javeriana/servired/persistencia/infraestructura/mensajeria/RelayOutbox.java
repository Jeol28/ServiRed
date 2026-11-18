package co.edu.javeriana.servired.persistencia.infraestructura.mensajeria;

import co.edu.javeriana.servired.persistencia.infraestructura.mensajeria.PublicadorConfirmado.EventoPendiente;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Relay del outbox (ADR-06): toma los eventos pendientes en orden, los publica
 * con confirmación y los marca como publicados. FOR UPDATE SKIP LOCKED permite
 * varias réplicas sin publicar dos veces el mismo lote. Si una publicación
 * falla se detiene el lote para conservar el orden y se reintenta en el
 * siguiente ciclo. La entrega es "al menos una vez": los consumidores son
 * idempotentes por idEvento.
 */
@Component
public class RelayOutbox {

    private static final Logger log = LoggerFactory.getLogger(RelayOutbox.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate transaccion;
    private final PublicadorConfirmado publicador;
    private final int tamanoLote;
    private final Counter publicados;
    private final Counter fallidos;

    public RelayOutbox(JdbcClient jdbc, TransactionTemplate transaccion, PublicadorConfirmado publicador,
                       MeterRegistry metricas, @Value("${servired.outbox.tamano-lote:50}") int tamanoLote) {
        this.jdbc = jdbc;
        this.transaccion = transaccion;
        this.publicador = publicador;
        this.tamanoLote = tamanoLote;
        this.publicados = Counter.builder("servired.outbox.publicados").register(metricas);
        this.fallidos = Counter.builder("servired.outbox.fallos").register(metricas);
        Gauge.builder("servired.outbox.pendientes", this, RelayOutbox::contarPendientes)
                .description("Eventos guardados que aún no se han publicado")
                .register(metricas);
        // RNF-R3: no basta contar; importa cuánto lleva esperando el más viejo (ADR-21).
        Gauge.builder("servired.outbox.mensaje.mas.viejo", this, RelayOutbox::edadDelMasViejo)
                .baseUnit("segundos")
                .description("Edad del evento guardado más viejo que aún no se ha publicado (0 si no hay)")
                .register(metricas);
    }

    @Scheduled(fixedDelayString = "${servired.outbox.intervalo-ms:500}")
    public void publicarPendientes() {
        transaccion.executeWithoutResult(estado -> {
            for (EventoPendiente evento : tomarLote()) {
                try {
                    publicador.publicar(evento);
                    marcarPublicado(evento.idEvento());
                    publicados.increment();
                    log.atInfo().addKeyValue("correlationId", evento.correlationId())
                            .addKeyValue("idEvento", evento.idEvento())
                            .addKeyValue("routingKey", evento.routingKey())
                            .log("evento publicado");
                } catch (RuntimeException e) {
                    marcarFallo(evento.idEvento(), e);
                    fallidos.increment();
                    log.atWarn().addKeyValue("correlationId", evento.correlationId())
                            .addKeyValue("idEvento", evento.idEvento())
                            .log("no se pudo publicar el evento; se reintentará: {}", e.getMessage());
                    return;
                }
            }
        });
    }

    private List<EventoPendiente> tomarLote() {
        return jdbc.sql("""
                        SELECT id_evento, tipo, version, routing_key, correlation_id, servicio_origen,
                               CAST(sobre AS text) AS sobre
                        FROM infraestructura.outbox
                        WHERE publicado_en IS NULL
                        ORDER BY creado_en
                        LIMIT :lote
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("lote", tamanoLote)
                .query((rs, n) -> new EventoPendiente(
                        rs.getObject("id_evento", UUID.class),
                        rs.getString("tipo"),
                        rs.getInt("version"),
                        rs.getString("routing_key"),
                        rs.getObject("correlation_id", UUID.class),
                        rs.getString("servicio_origen"),
                        rs.getString("sobre")))
                .list();
    }

    private void marcarPublicado(UUID idEvento) {
        jdbc.sql("UPDATE infraestructura.outbox SET publicado_en = now(), intentos = intentos + 1 WHERE id_evento = :id")
                .param("id", idEvento)
                .update();
    }

    private void marcarFallo(UUID idEvento, RuntimeException e) {
        jdbc.sql("""
                        UPDATE infraestructura.outbox
                        SET intentos = intentos + 1, ultimo_error = left(:error, 500)
                        WHERE id_evento = :id
                        """)
                .param("error", String.valueOf(e.getMessage()))
                .param("id", idEvento)
                .update();
    }

    private double edadDelMasViejo() {
        try {
            return jdbc.sql("""
                            SELECT COALESCE(EXTRACT(EPOCH FROM now() - min(creado_en)), 0)
                            FROM infraestructura.outbox WHERE publicado_en IS NULL
                            """)
                    .query(Double.class).single();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private double contarPendientes() {
        try {
            return jdbc.sql("SELECT count(*) FROM infraestructura.outbox WHERE publicado_en IS NULL")
                    .query(Long.class).single();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }
}
