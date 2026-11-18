package co.edu.javeriana.servired.persistencia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.javeriana.servired.persistencia.soporte.AlmacenDePrueba;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * R14: la vista de Indicadores se verifica y se reconstruye desde los datos de origen
 * (reportes, votos que cuentan y casos), contra PostgreSQL real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {"servired.claves.indicadores=clave-indicadores-prueba",
        "servired.claves.reportes=clave-reportes-de-prueba"})
class ReconstruccionIndicadoresIntegracionTest {

    static final String CLAVE = "clave-indicadores-prueba";
    static final String EMPRESA = "a0000000-0000-4000-8000-000000000001";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.1-management-alpine");

    @Container
    static GenericContainer<?> almacen = AlmacenDePrueba.crear();

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry r) {
        AlmacenDePrueba.registrar(almacen, r);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private ResultActions reconstruir(boolean aplicar, String clave) throws Exception {
        return mvc.perform(post("/interno/v1/indicadores/reconstruccion").header("X-Clave-Servicio", clave)
                .header("X-Correlation-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"aplicar\":" + aplicar + "}"));
    }

    /** Un reporte con 3 votos de CORTE que cuentan, 1 que no (ADR-22) y un caso en atención. */
    private UUID sembrarOrigen() {
        UUID reporte = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO reportes.reportes (id, id_ciudadano, tipo_servicio, latitud, longitud, codigo_municipio,
                    inicio_estimado, magnitud, estado, fecha_registro, fecha_restablecimiento, duracion_minutos)
                VALUES (?, ?, 'ENERGIA', 4.6, -74.08, '11001', '2026-11-10T08:00:00Z', 'BARRIO', 'RESTABLECIDO',
                        '2026-11-10T10:00:00Z', '2026-11-10T12:00:00Z', 240)""", reporte, UUID.randomUUID());
        for (int i = 0; i < 4; i++) {
            jdbc.update("INSERT INTO reportes.validaciones (id_reporte, objeto, id_ciudadano, sentido, fecha, cuenta) "
                    + "VALUES (?, 'CORTE', ?, 'CONFIRMA', '2026-11-10T11:00:00Z', ?)", reporte, UUID.randomUUID(), i < 3);
        }
        UUID caso = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO atencion.casos (id, id_reporte, tipo_servicio, codigo_municipio, magnitud, estado, abierto_en,
                    id_empresa, prioridad)
                VALUES (?, ?, 'ENERGIA', '11001', 'BARRIO', 'EN_ATENCION', '2026-11-10T10:00:01Z', ?::uuid, 'ALTA')""",
                caso, reporte, EMPRESA);
        jdbc.update("INSERT INTO atencion.historia_caso (id_caso, estado, fecha, detalle) "
                + "VALUES (?, 'EN_ATENCION', '2026-11-10T10:30:00Z', 'En atención')", caso);
        return reporte;
    }

    private String vista(UUID reporte, String columna) {
        return jdbc.queryForObject("SELECT " + columna + "::text FROM indicadores.vista_reportes WHERE id_reporte = ?",
                String.class, reporte);
    }

    @Test
    void verificaSinTocarYReconstruyeLaVistaDesdeElOrigen() throws Exception {
        UUID reporte = sembrarOrigen();

        // 1. La vista no lo tiene (p. ej. se perdió su reporte.creado): verificar lo informa y no cambia nada.
        reconstruir(false, CLAVE).andExpect(status().isOk())
                .andExpect(jsonPath("$.faltantes").value(1))
                .andExpect(jsonPath("$.aplicada").value(false));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM indicadores.vista_reportes", Integer.class)).isZero();

        // 2. Reconstruir la deja como el origen: votos que cuentan, caso, empresa y restablecimiento.
        reconstruir(true, CLAVE).andExpect(status().isOk()).andExpect(jsonPath("$.aplicada").value(true));
        assertThat(vista(reporte, "confirmaciones_netas")).as("solo los 3 votos que cuentan").isEqualTo("3");
        assertThat(vista(reporte, "validado")).isEqualTo("true");
        assertThat(vista(reporte, "estado_caso")).isEqualTo("EN_ATENCION");
        assertThat(vista(reporte, "id_empresa")).isEqualTo(EMPRESA);
        assertThat(vista(reporte, "duracion_minutos")).isEqualTo("240");

        // 3. Se desalinea (un valor alterado y una fila huérfana): verificar lo detecta por columna.
        jdbc.update("UPDATE indicadores.vista_reportes SET validado = false, prioridad = 'BAJA' WHERE id_reporte = ?",
                reporte);
        jdbc.update("INSERT INTO indicadores.vista_reportes (id_reporte, tipo_servicio) VALUES (?, 'GAS')",
                UUID.randomUUID());
        reconstruir(false, CLAVE)
                .andExpect(jsonPath("$.distintas").value(1))
                .andExpect(jsonPath("$.distintasPorColumna.validado").value(1))
                .andExpect(jsonPath("$.distintasPorColumna.prioridad").value(1))
                .andExpect(jsonPath("$.sobrantes").value(1));

        // 4. Reconstruir la alinea de nuevo, y una verificación posterior no encuentra diferencias.
        reconstruir(true, CLAVE);
        reconstruir(false, CLAVE)
                .andExpect(jsonPath("$.faltantes").value(0))
                .andExpect(jsonPath("$.sobrantes").value(0))
                .andExpect(jsonPath("$.distintas").value(0));
    }

    @Test
    void soloIndicadoresPuedeReconstruir() throws Exception {
        reconstruir(true, "clave-reportes-de-prueba").andExpect(status().isForbidden());
    }
}
