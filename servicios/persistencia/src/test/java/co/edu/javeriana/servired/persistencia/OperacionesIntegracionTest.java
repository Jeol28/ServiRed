package co.edu.javeriana.servired.persistencia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Operaciones de negocio contra PostgreSQL y RabbitMQ reales: autorización
 * por servicio, transacción con outbox, idempotencia, resolución DIVIPOLA y
 * publicación confirmada del relay.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "servired.claves.reportes=clave-reportes-de-prueba",
        "servired.claves.atencion=clave-atencion-de-prueba",
        "servired.outbox.intervalo-ms=100"})
class OperacionesIntegracionTest {

    static final String CLAVE_REPORTES = "clave-reportes-de-prueba";
    static final String CLAVE_ATENCION = "clave-atencion-de-prueba";
    static final String COLA_PRUEBA = "prueba.reportes";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.1-management-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AmqpAdmin admin;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @BeforeEach
    void declararTopologiaMinima() {
        TopicExchange exchange = new TopicExchange("servired.eventos");
        Queue cola = new Queue(COLA_PRUEBA);
        admin.declareExchange(exchange);
        admin.declareQueue(cola);
        admin.declareBinding(BindingBuilder.bind(cola).to(exchange).with("reporte.#"));
        admin.purgeQueue(COLA_PRUEBA);
    }

    private static String cuerpoGuardar(UUID idReporte, String correlacion, String municipio) {
        return """
                {"reporte":{"idReporte":"%s","idCiudadano":"%s","tipoServicio":"ENERGIA",
                  "latitud":4.6287,"longitud":-74.0648,"codigoMunicipio":"%s",
                  "inicioEstimado":"2026-11-14T10:00:00Z","magnitud":"CUADRA",
                  "descripcion":"Sin luz en la cuadra","fechaRegistro":"2026-11-14T10:05:00Z"},
                 "evento":{"idEvento":"%s","tipo":"reporte.creado","version":1,
                  "fechaOcurrencia":"2026-11-14T10:05:00Z","correlationId":"%s","origen":"reportes",
                  "datos":{"idReporte":"%s","codigoMunicipio":"%s"}}}
                """.formatted(idReporte, UUID.randomUUID(), municipio, UUID.randomUUID(), correlacion,
                idReporte, municipio);
    }

    private static MockHttpServletRequestBuilder guardar(String clave, String correlacion, String cuerpo) {
        return post("/interno/v1/reportes/guardar")
                .header("X-Clave-Servicio", clave)
                .header("X-Correlation-Id", correlacion)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpo);
    }

    @Test
    void guardaElReporteConSuHistoriaYPublicaReporteCreado() throws Exception {
        UUID id = UUID.randomUUID();
        String correlacion = UUID.randomUUID().toString();

        mvc.perform(guardar(CLAVE_REPORTES, correlacion, cuerpoGuardar(id, correlacion, "11001")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idReporte").value(id.toString()))
                .andExpect(jsonPath("$.estado").value("RECIBIDO"))
                .andExpect(jsonPath("$.nombreMunicipio").value("BOGOTÁ, D.C."))
                .andExpect(jsonPath("$.historia[0].estado").value("RECIBIDO"));

        // Otras pruebas también publican en la cola: se busca el mensaje de esta transacción.
        Message mensaje = await().atMost(Duration.ofSeconds(10))
                .until(() -> rabbitTemplate.receive(COLA_PRUEBA),
                        m -> m != null && correlacion.equals(m.getMessageProperties().getCorrelationId()));
        assertThat(mensaje.getMessageProperties().getReceivedRoutingKey()).isEqualTo("reporte.creado.v1");
        assertThat(mensaje.getMessageProperties().getCorrelationId()).isEqualTo(correlacion);
        assertThat(new String(mensaje.getBody())).contains(id.toString());
        await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.queryForObject(
                "SELECT publicado_en IS NOT NULL FROM infraestructura.outbox WHERE correlation_id = ?::uuid",
                Boolean.class, correlacion));
    }

    @Test
    void esIdempotentePorCorrelationId() throws Exception {
        UUID original = UUID.randomUUID();
        String correlacion = UUID.randomUUID().toString();
        mvc.perform(guardar(CLAVE_REPORTES, correlacion, cuerpoGuardar(original, correlacion, "11001")))
                .andExpect(status().isCreated());

        mvc.perform(guardar(CLAVE_REPORTES, correlacion, cuerpoGuardar(UUID.randomUUID(), correlacion, "11001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idReporte").value(original.toString()));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM infraestructura.outbox WHERE correlation_id = ?::uuid",
                Integer.class, correlacion)).isEqualTo(1);
    }

    @Test
    void rechazaSinClaveConClaveInvalidaOSinCorrelacion() throws Exception {
        String correlacion = UUID.randomUUID().toString();
        String cuerpo = cuerpoGuardar(UUID.randomUUID(), correlacion, "11001");

        mvc.perform(post("/interno/v1/reportes/guardar").header("X-Correlation-Id", correlacion)
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo)).andExpect(status().isUnauthorized());
        mvc.perform(guardar("clave-que-no-existe-123", correlacion, cuerpo)).andExpect(status().isUnauthorized());
        mvc.perform(post("/interno/v1/reportes/guardar").header("X-Clave-Servicio", CLAVE_REPORTES)
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo)).andExpect(status().isBadRequest());
    }

    @Test
    void atencionNoPuedeGuardarReportes() throws Exception {
        String correlacion = UUID.randomUUID().toString();

        mvc.perform(guardar(CLAVE_ATENCION, correlacion, cuerpoGuardar(UUID.randomUUID(), correlacion, "11001")))
                .andExpect(status().isForbidden());
    }

    @Test
    void rechazaMunicipiosInexistentesSinDejarRastro() throws Exception {
        String correlacion = UUID.randomUUID().toString();

        mvc.perform(guardar(CLAVE_REPORTES, correlacion, cuerpoGuardar(UUID.randomUUID(), correlacion, "99999")))
                .andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM infraestructura.solicitudes_procesadas WHERE correlation_id = ?::uuid
                """, Integer.class, correlacion)).isZero();
    }

    @Test
    void consultaUnReporteYResponde404SiNoExiste() throws Exception {
        UUID id = UUID.randomUUID();
        String correlacion = UUID.randomUUID().toString();
        mvc.perform(guardar(CLAVE_REPORTES, correlacion, cuerpoGuardar(id, correlacion, "05001")));

        mvc.perform(get("/interno/v1/reportes/" + id).header("X-Clave-Servicio", CLAVE_REPORTES)
                        .header("X-Correlation-Id", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codigoMunicipio").value("05001"));
        mvc.perform(get("/interno/v1/reportes/" + UUID.randomUUID()).header("X-Clave-Servicio", CLAVE_REPORTES)
                        .header("X-Correlation-Id", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void resuelveElMunicipioMasCercano() throws Exception {
        mvc.perform(get("/interno/v1/catalogo/municipio-cercano?latitud=4.6287&longitud=-74.0648")
                        .header("X-Clave-Servicio", CLAVE_REPORTES)
                        .header("X-Correlation-Id", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codigo").value("11001"))
                .andExpect(jsonPath("$.departamento").value("BOGOTÁ, D.C."));
        mvc.perform(get("/interno/v1/catalogo/municipio-cercano?latitud=3.42&longitud=-76.52")
                        .header("X-Clave-Servicio", CLAVE_REPORTES)
                        .header("X-Correlation-Id", UUID.randomUUID().toString()))
                .andExpect(jsonPath("$.codigo").value("76001"));
    }

    @Test
    void abreUnSoloCasoPorReporte() throws Exception {
        UUID idReporte = UUID.randomUUID();
        String correlacion = UUID.randomUUID().toString();
        String cuerpo = """
                {"idCaso":"%s","idReporte":"%s","tipoServicio":"ENERGIA","codigoMunicipio":"11001",
                 "magnitud":"CUADRA","abiertoEn":"2026-11-14T10:06:00Z"}""";
        UUID idCaso = UUID.randomUUID();

        mvc.perform(post("/interno/v1/casos/abrir").header("X-Clave-Servicio", CLAVE_ATENCION)
                        .header("X-Correlation-Id", correlacion).contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo.formatted(idCaso, idReporte)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("RECIBIDO"));
        // Redelivery del mismo evento (mismo correlationId) con otro idCaso
        mvc.perform(post("/interno/v1/casos/abrir").header("X-Clave-Servicio", CLAVE_ATENCION)
                        .header("X-Correlation-Id", correlacion).contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo.formatted(UUID.randomUUID(), idReporte)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idCaso").value(idCaso.toString()));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM atencion.casos WHERE id_reporte = ?",
                Integer.class, idReporte)).isEqualTo(1);
    }

    @Test
    void laSemillaTieneTodaLaDivipola() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalogo.departamentos", Integer.class)).isEqualTo(33);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalogo.municipios", Integer.class)).isEqualTo(1122);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalogo.tipos_servicio", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalogo.coberturas", Integer.class)).isPositive();
    }
}
