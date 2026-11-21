package co.edu.javeriana.servired.persistencia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.javeriana.servired.persistencia.dominio.IdCorrelacion;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@Testcontainers
class PersistenciaIntegracionTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = co.edu.javeriana.servired.persistencia.soporte.BaseDePrueba.crear();

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.1-management-alpine");

    @Container
    static org.testcontainers.containers.GenericContainer<?> almacen =
            co.edu.javeriana.servired.persistencia.soporte.AlmacenDePrueba.crear();

    @org.springframework.test.context.DynamicPropertySource
    static void propiedadesAlmacen(org.springframework.test.context.DynamicPropertyRegistry r) {
        co.edu.javeriana.servired.persistencia.soporte.AlmacenDePrueba.registrar(almacen, r);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvc mvc;

    @Test
    void readinessEstaUpConSusDependencias() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void exponeMetricasPrometheus() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    }

    @Test
    void propagaElIdDeCorrelacion() throws Exception {
        String id = UUID.randomUUID().toString();

        mvc.perform(get("/actuator/health").header(IdCorrelacion.CABECERA, id))
                .andExpect(header().string(IdCorrelacion.CABECERA, id));
    }

    @Test
    void generaIdDeCorrelacionSiFalta() throws Exception {
        String generado = mvc.perform(get("/actuator/health"))
                .andReturn().getResponse().getHeader(IdCorrelacion.CABECERA);

        assertThat(UUID.fromString(generado)).isNotNull();
    }

    @Test
    void lasMigracionesCreanUnEsquemaPorDominio() {
        List<String> esquemas = jdbc.queryForList(
                "SELECT schema_name FROM information_schema.schemata", String.class);

        assertThat(esquemas).contains("catalogo", "identidad", "reportes", "atencion",
                "indicadores", "infraestructura");
    }

    @Test
    void existenLasTablasDeOutboxEIdempotencia() {
        Integer tablas = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'infraestructura'
                  AND table_name IN ('outbox', 'solicitudes_procesadas')
                """, Integer.class);

        assertThat(tablas).isEqualTo(2);
    }
}
