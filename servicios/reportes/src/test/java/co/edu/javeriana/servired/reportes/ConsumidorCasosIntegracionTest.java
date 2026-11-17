package co.edu.javeriana.servired.reportes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import co.edu.javeriana.servired.reportes.aplicacion.CrearReporte;
import co.edu.javeriana.servired.reportes.aplicacion.PersistenciaEnMemoria;
import co.edu.javeriana.servired.reportes.dominio.EstadoReporte;
import co.edu.javeriana.servired.reportes.dominio.IdCorrelacion;
import co.edu.javeriana.servired.reportes.dominio.Magnitud;
import co.edu.javeriana.servired.reportes.dominio.Reporte;
import co.edu.javeriana.servired.reportes.dominio.TipoServicio;
import co.edu.javeriana.servired.reportes.dominio.Ubicacion;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** El consumidor real de caso.* contra un RabbitMQ real con la topología del compose; Persistencia en memoria. */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "servired.consumidor.max-intentos=2")
class ConsumidorCasosIntegracionTest {

    static final String COLA = "reportes.eventos-casos";

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.1-management-alpine");

    @TestConfiguration
    static class Dobles {
        @Bean
        @Primary
        PersistenciaEnMemoria persistenciaEnMemoria() {
            return new PersistenciaEnMemoria();
        }
    }

    @BeforeAll
    static void topologia() throws Exception {
        var fabrica = new com.rabbitmq.client.ConnectionFactory();
        fabrica.setHost(rabbit.getHost());
        fabrica.setPort(rabbit.getAmqpPort());
        fabrica.setUsername(rabbit.getAdminUsername());
        fabrica.setPassword(rabbit.getAdminPassword());
        try (var conexion = fabrica.newConnection(); var canal = conexion.createChannel()) {
            canal.exchangeDeclare("servired.dlx", "direct", true);
            canal.exchangeDeclare("servired.reintentos", "direct", true);
            canal.queueDeclare(COLA, true, false, false, Map.of("x-queue-type", "quorum",
                    "x-dead-letter-exchange", "servired.dlx", "x-dead-letter-routing-key", COLA));
            canal.queueDeclare(COLA + ".reintento", true, false, false, Map.of("x-queue-type", "quorum",
                    "x-message-ttl", 300, "x-dead-letter-exchange", "servired.reintentos", "x-dead-letter-routing-key", COLA));
            canal.queueDeclare(COLA + ".dlq", true, false, false, Map.of("x-queue-type", "quorum"));
            canal.queueBind(COLA + ".dlq", "servired.dlx", COLA);
            canal.queueBind(COLA, "servired.reintentos", COLA);
        }
    }

    @Autowired
    PersistenciaEnMemoria persistencia;

    @Autowired
    RabbitTemplate rabbitTemplate;

    private Reporte crearReporte() {
        return new CrearReporte(persistencia, persistencia, Clock.systemUTC()).ejecutar(UUID.randomUUID(),
                new CrearReporte.Comando(TipoServicio.ENERGIA, new Ubicacion(4.6, -74.1), Instant.now().minusSeconds(3600),
                        Magnitud.CUADRA, null), IdCorrelacion.nuevo());
    }

    private void publicar(String tipo, Map<String, Object> datos) throws Exception {
        String cuerpo = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                "idEvento", UUID.randomUUID().toString(), "tipo", tipo, "version", 1,
                "fechaOcurrencia", Instant.now().toString(), "correlationId", UUID.randomUUID().toString(),
                "origen", "atencion", "datos", datos));
        publicarCrudo(cuerpo);
    }

    private void publicarCrudo(String cuerpo) {
        var p = new MessageProperties();
        p.setContentType("application/json");
        rabbitTemplate.send("", COLA, new Message(cuerpo.getBytes(StandardCharsets.UTF_8), p));
    }

    private EstadoReporte estado(Reporte r) {
        return persistencia.reportes.get(r.idReporte()).estado();
    }

    @Test
    void reflejaElCicloDelCasoEnElReporte() throws Exception {
        Reporte r = crearReporte();

        publicar("caso.actualizado", Map.of("idCaso", UUID.randomUUID().toString(), "idReporte", r.idReporte().toString(),
                "estado", "EN_ATENCION", "prioridad", "MEDIA"));
        await().atMost(Duration.ofSeconds(10)).until(() -> estado(r) == EstadoReporte.EN_ATENCION);

        publicar("caso.cerrado", Map.of("idCaso", UUID.randomUUID().toString(), "idReporte", r.idReporte().toString(),
                "fechaCierre", Instant.now().toString(), "resolucion", "Transformador reemplazado"));
        await().atMost(Duration.ofSeconds(10)).until(() -> estado(r) == EstadoReporte.CERRADO);
        assertThat(persistencia.reportes.get(r.idReporte()).historia()).last()
                .extracting(Reporte.EntradaHistoria::detalle).asString().contains("Transformador reemplazado");
    }

    @Test
    void losMensajesIlegiblesYLosReportesInexistentesVanALaDlq() throws Exception {
        publicarCrudo("no es json");
        publicar("caso.cerrado", Map.of("idCaso", UUID.randomUUID().toString(), "idReporte", UUID.randomUUID().toString(),
                "fechaCierre", Instant.now().toString()));

        await().atMost(Duration.ofSeconds(10)).until(() -> contarDlq() >= 2);
    }

    private final java.util.concurrent.atomic.AtomicInteger enDlq = new java.util.concurrent.atomic.AtomicInteger();

    /** Consume (y cuenta) lo que llegó a la DLQ. */
    private int contarDlq() {
        while (rabbitTemplate.receive(COLA + ".dlq") != null) {
            enDlq.incrementAndGet();
        }
        return enDlq.get();
    }
}
