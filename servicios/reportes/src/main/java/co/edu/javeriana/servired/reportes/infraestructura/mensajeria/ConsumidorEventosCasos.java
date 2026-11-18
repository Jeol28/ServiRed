package co.edu.javeriana.servired.reportes.infraestructura.mensajeria;

import co.edu.javeriana.servired.reportes.aplicacion.SeguimientoDeCasos;
import co.edu.javeriana.servired.reportes.dominio.IdCorrelacion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Consume caso.actualizado y caso.cerrado (cola reportes.eventos-casos) con la
 * misma política que Atención (ADR-10): ack manual; falla transitoria → copia a
 * la cola .reintento con x-intentos + 1; falla permanente o intentos agotados →
 * DLQ. La idempotencia la garantiza Persistencia por idEvento.
 */
@Component
public class ConsumidorEventosCasos {

    public static final String COLA = "reportes.eventos-casos";
    public static final String CABECERA_INTENTOS = "x-intentos";
    private static final Logger log = LoggerFactory.getLogger(ConsumidorEventosCasos.class);

    private final SeguimientoDeCasos seguimiento;
    private final ObjectMapper json;
    private final RabbitTemplate rabbit;
    private final SondaCola sonda;
    private final int maxIntentos;

    public ConsumidorEventosCasos(SeguimientoDeCasos seguimiento, ObjectMapper json, RabbitTemplate rabbit,
                                  @Value("${servired.consumidor.max-intentos:5}") int maxIntentos,
                                  MeterRegistry metricas) {
        this.seguimiento = seguimiento;
        this.json = json;
        this.rabbit = rabbit;
        this.maxIntentos = maxIntentos;
        this.sonda = new SondaCola(COLA, metricas, Clock.systemUTC());
    }

    @RabbitListener(queues = COLA, ackMode = "MANUAL")
    public void recibir(Message mensaje, Channel canal) throws IOException {
        long etiqueta = mensaje.getMessageProperties().getDeliveryTag();
        // Sonda de antigüedad (ADR-21): se registra y se confirma; no es un evento de negocio.
        if (SondaCola.esSonda(mensaje)) {
            sonda.recibir(mensaje);
            canal.basicAck(etiqueta, false);
            return;
        }
        JsonNode sobre;
        try {
            sobre = json.readTree(mensaje.getBody());
            UUID.fromString(sobre.path("idEvento").asText());
            UUID.fromString(sobre.path("correlationId").asText());
        } catch (IOException | IllegalArgumentException e) {
            log.warn("mensaje ilegible en {}, va a la DLQ", COLA);
            canal.basicReject(etiqueta, false);
            return;
        }
        String correlacion = sobre.path("correlationId").asText();
        MDC.put("correlationId", correlacion);
        try {
            procesar(sobre, new IdCorrelacion(correlacion));
            canal.basicAck(etiqueta, false);
        } catch (FallaPermanente e) {
            log.warn("falla permanente, va a la DLQ: {}", e.getMessage());
            canal.basicReject(etiqueta, false);
        } catch (RuntimeException e) {
            reintentarOEnviarADlq(mensaje, canal, etiqueta, e);
        } finally {
            MDC.remove("correlationId");
        }
    }

    private void procesar(JsonNode sobre, IdCorrelacion correlacion) {
        String tipo = sobre.path("tipo").asText();
        if (sobre.path("version").asInt() != 1 || !(tipo.equals("caso.actualizado") || tipo.equals("caso.cerrado"))) {
            log.debug("evento no manejado por Reportes: {}", tipo);
            return;
        }
        JsonNode datos = sobre.path("datos");
        UUID idReporte;
        Instant fecha;
        try {
            idReporte = UUID.fromString(datos.path("idReporte").asText());
            fecha = Instant.parse(sobre.path("fechaOcurrencia").asText());
        } catch (RuntimeException e) {
            throw new FallaPermanente("datos de " + tipo + " inválidos");
        }
        try {
            seguimiento.aplicar(tipo, UUID.fromString(sobre.path("idEvento").asText()), idReporte,
                    datos.path("estado").asText(null), datos.path("resolucion").asText(null), fecha, correlacion)
                    .ifPresent(c -> log.atInfo().addKeyValue("idReporte", idReporte)
                            .addKeyValue("estado", c.estadoNuevo()).log("estado del reporte actualizado por el caso"));
        } catch (HttpClientErrorException.NotFound e) {
            throw new FallaPermanente("el reporte " + idReporte + " no existe");
        }
    }

    private void reintentarOEnviarADlq(Message mensaje, Channel canal, long etiqueta, RuntimeException error)
            throws IOException {
        Object previo = mensaje.getMessageProperties().getHeaders().get(CABECERA_INTENTOS);
        int intentos = (previo instanceof Number n ? n.intValue() : 0) + 1;
        if (intentos >= maxIntentos) {
            log.error("falla transitoria tras {} intentos, va a la DLQ: {}", intentos, error.getMessage());
            canal.basicReject(etiqueta, false);
            return;
        }
        log.warn("falla transitoria (intento {}/{}), se reintenta: {}", intentos, maxIntentos, error.getMessage());
        try {
            var propiedades = mensaje.getMessageProperties();
            propiedades.setHeader(CABECERA_INTENTOS, intentos);
            propiedades.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            rabbit.invoke(operaciones -> {
                operaciones.send("", COLA + ".reintento", new Message(mensaje.getBody(), propiedades));
                operaciones.waitForConfirmsOrDie(5000);
                return null;
            });
            canal.basicAck(etiqueta, false);
        } catch (RuntimeException e) {
            log.error("no se pudo programar el reintento, se reencola: {}", e.getMessage());
            canal.basicNack(etiqueta, false, true);
        }
    }

    /** El mensaje nunca podrá procesarse: va directo a la DLQ. */
    static class FallaPermanente extends RuntimeException {
        FallaPermanente(String mensaje) {
            super(mensaje);
        }
    }
}
