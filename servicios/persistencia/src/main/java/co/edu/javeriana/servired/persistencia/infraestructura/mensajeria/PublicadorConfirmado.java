package co.edu.javeriana.servired.persistencia.infraestructura.mensajeria;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Publica un evento y espera la confirmación del broker (publisher confirms).
 * Los mensajes son persistentes y obligatorios: si ninguna cola los recibe,
 * el broker los devuelve y la publicación se considera fallida.
 */
@Component
public class PublicadorConfirmado {

    private final RabbitTemplate rabbit;
    private final String exchange;
    private final long esperaConfirmacionMs;
    private final Set<String> devueltos = ConcurrentHashMap.newKeySet();

    public PublicadorConfirmado(RabbitTemplate rabbit,
                                @Value("${servired.eventos.exchange:servired.eventos}") String exchange,
                                @Value("${servired.eventos.espera-confirmacion-ms:5000}") long esperaConfirmacionMs) {
        this.rabbit = rabbit;
        this.exchange = exchange;
        this.esperaConfirmacionMs = esperaConfirmacionMs;
        rabbit.setMandatory(true);
        rabbit.setReturnsCallback(r -> devueltos.add(r.getMessage().getMessageProperties().getMessageId()));
    }

    public void publicar(EventoPendiente evento) {
        MessageProperties propiedades = new MessageProperties();
        propiedades.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        propiedades.setContentEncoding(StandardCharsets.UTF_8.name());
        propiedades.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        propiedades.setMessageId(evento.idEvento().toString());
        propiedades.setCorrelationId(evento.correlationId().toString());
        propiedades.setType(evento.tipo());
        propiedades.setAppId(evento.servicioOrigen());
        propiedades.setHeader("x-correlation-id", evento.correlationId().toString());
        propiedades.setHeader("x-version", evento.version());
        Message mensaje = new Message(evento.sobre().getBytes(StandardCharsets.UTF_8), propiedades);

        String id = evento.idEvento().toString();
        rabbit.invoke(operaciones -> {
            operaciones.send(exchange, evento.routingKey(), mensaje);
            operaciones.waitForConfirmsOrDie(esperaConfirmacionMs);
            return null;
        });
        if (devueltos.remove(id)) {
            throw new IllegalStateException("Ninguna cola recibió " + evento.routingKey());
        }
    }

    /**
     * Mensaje sonda (ADR-21): viaja por el mismo exchange que los eventos y llega a cada
     * cola consumidora (routing key {@code sonda.v1}). Cada consumidor registra cuándo se
     * publicó la última que recibió; así se acota la edad del mensaje más viejo de su cola.
     */
    public void publicarSonda(long publicadaEnMs) {
        MessageProperties propiedades = new MessageProperties();
        propiedades.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        propiedades.setDeliveryMode(MessageDeliveryMode.NON_PERSISTENT);
        String id = "sonda-" + UUID.randomUUID();
        propiedades.setMessageId(id);
        propiedades.setType(SONDA);
        propiedades.setAppId("persistencia");
        propiedades.setHeader(CABECERA_SONDA, publicadaEnMs);
        Message mensaje = new Message("{}".getBytes(StandardCharsets.UTF_8), propiedades);
        rabbit.invoke(operaciones -> {
            operaciones.send(exchange, SONDA, mensaje);
            operaciones.waitForConfirmsOrDie(esperaConfirmacionMs);
            return null;
        });
        if (devueltos.remove(id)) {
            throw new IllegalStateException("Ninguna cola recibió la sonda: falta el binding " + SONDA);
        }
    }

    public static final String SONDA = "sonda.v1";
    public static final String CABECERA_SONDA = "x-sonda-publicada-ms";

    public record EventoPendiente(UUID idEvento, String tipo, int version, String routingKey,
                                  UUID correlationId, String servicioOrigen, String sobre) {
    }
}
