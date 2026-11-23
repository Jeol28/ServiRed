package co.edu.javeriana.servired.persistencia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.javeriana.servired.persistencia.soporte.AlmacenDePrueba;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Borrado de todo lo relacionado con las empresas de ejemplo (ADR-36): la función que llama V23,
 * sobre datos sembrados como los que dejó V22. Lo ajeno se conserva, la cadena de auditoría queda
 * íntegra tras el reencadenamiento y el ancla que cubría lo borrado figura como reemplazada.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {"servired.claves.auditoria=clave-auditoria-prueba",
        "servired.auditoria.archivo.activo=false", "servired.auditoria.anclas.activo=false"})
class BorradoEmpresasEjemploIntegracionTest {

    static final String EJEMPLO = "a0000000-0000-4000-8000-000000000001";
    static final String ENEL = "619a2e48-402d-5782-9daf-57f16169b157";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = co.edu.javeriana.servired.persistencia.soporte.BaseDePrueba.crear();

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

    private UUID cuenta(String rol, String empresa) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO identidad.cuentas (id, correo, hash_clave, rol, id_empresa, creada_en) "
                + "VALUES (?, ?, 'x', ?, ?::uuid, now())", id, id + "@e.co", rol, empresa);
        jdbc.update("INSERT INTO identidad.sesiones (id, id_cuenta, hash_refresco, expira_en) "
                + "VALUES (?, ?, ?, now() + interval '1 day')", UUID.randomUUID(), id, UUID.randomUUID().toString());
        return id;
    }

    private UUID[] reporteConCaso(String empresa, UUID operador) {
        UUID reporte = UUID.randomUUID();
        UUID caso = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO reportes.reportes (id, id_ciudadano, tipo_servicio, latitud, longitud, codigo_municipio,
                    inicio_estimado, magnitud, estado, fecha_registro)
                VALUES (?, ?, 'ENERGIA', 4.65, -74.06, '11001', now(), 'CUADRA', 'RECIBIDO', now())""",
                reporte, UUID.randomUUID());
        jdbc.update("INSERT INTO reportes.historia_reporte (id_reporte, estado, fecha) VALUES (?, 'RECIBIDO', now())",
                reporte);
        jdbc.update("""
                INSERT INTO indicadores.vista_reportes (id_reporte, tipo_servicio, codigo_municipio, latitud, longitud,
                    fecha_registro, id_empresa) VALUES (?, 'ENERGIA', '11001', 4.65, -74.06, now(), ?::uuid)""",
                reporte, empresa);
        jdbc.update("""
                INSERT INTO atencion.casos (id, id_reporte, tipo_servicio, codigo_municipio, magnitud, estado, abierto_en,
                    id_empresa, prioridad, asignado_a)
                VALUES (?, ?, 'ENERGIA', '11001', 'CUADRA', 'EN_ATENCION', now(), ?::uuid, 'MEDIA', ?)""",
                caso, reporte, empresa, operador);
        jdbc.update("INSERT INTO atencion.historia_caso (id_caso, estado, fecha, detalle, autor) "
                + "VALUES (?, 'EN_ATENCION', now(), 'Atendido', ?)", caso, operador);
        return new UUID[]{reporte, caso};
    }

    private void evento(UUID mencionado) {
        jdbc.update("""
                INSERT INTO infraestructura.outbox (id_evento, tipo, version, routing_key, correlation_id, servicio_origen, sobre)
                VALUES (?, 'caso.actualizado', 1, 'caso.actualizado.v1', ?, 'atencion', ?::jsonb)""",
                UUID.randomUUID(), UUID.randomUUID(), "{\"datos\":{\"idCaso\":\"" + mencionado + "\"}}");
    }

    private void auditar(String detalle) throws Exception {
        mvc.perform(post("/interno/v1/auditoria/registros").header("X-Clave-Servicio", "clave-auditoria-prueba")
                        .header("X-Correlation-Id", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idEvento":"%s","tipo":"accion.auditada","version":1,"origen":"atencion",
                                 "categoria":"ADMINISTRACION","accion":"ENRUTAR","resultado":"EXITO","detalle":%s,
                                 "ocurridoEn":"2026-11-17T10:00:00Z"}""".formatted(UUID.randomUUID(), detalle)))
                .andExpect(status().is2xxSuccessful());
    }

    private long contar(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void borraTodoLoDeLasEmpresasDeEjemploConservaLoAjenoYReencadenaLaAuditoria() throws Exception {
        // Lo que dejó V22: la empresa de ejemplo (aquí, sembrada), su operador y un caso que fue suyo.
        jdbc.update("INSERT INTO catalogo.empresas (id, nit, nombre) VALUES (?::uuid, '900000001-1', 'Ejemplo')", EJEMPLO);
        UUID operador = cuenta("OPERADOR", EJEMPLO);
        UUID[] deEjemplo = reporteConCaso(EJEMPLO, operador);
        jdbc.update("INSERT INTO catalogo.reasignacion_empresas (objeto, id_objeto, empresa_anterior, detalle, migracion) "
                + "VALUES ('CUENTA', ?, ?::uuid, 'x', 'V22'), ('CASO', ?, ?::uuid, 'x', 'V22')",
                operador, EJEMPLO, deEjemplo[1], EJEMPLO);
        // Lo ajeno: un caso de Enel con su operador, y una entrada que el operador de ejemplo escribió en él.
        UUID operadorEnel = cuenta("OPERADOR", ENEL);
        UUID[] ajeno = reporteConCaso(ENEL, operadorEnel);
        jdbc.update("INSERT INTO atencion.historia_caso (id_caso, estado, fecha, detalle, autor) "
                + "VALUES (?, 'EN_ATENCION', now(), 'Nota', ?)", ajeno[1], operador);
        evento(deEjemplo[1]);
        evento(ajeno[1]);
        jdbc.update("""
                INSERT INTO infraestructura.solicitudes_procesadas (correlation_id, operacion, servicio_origen, respuesta)
                VALUES (?, 'abrirCaso', 'atencion', ?::jsonb), (?, 'abrirCaso', 'atencion', ?::jsonb)""",
                UUID.randomUUID(), "{\"idCaso\":\"" + deEjemplo[1] + "\"}", UUID.randomUUID(),
                "{\"idCaso\":\"" + ajeno[1] + "\"}");
        // Auditoría: dos antes, uno que nombra la empresa de ejemplo, dos después; y el ancla del día.
        auditar("{\"n\":1}");
        auditar("{\"n\":2}");
        auditar("{\"idEmpresa\":\"" + EJEMPLO + "\"}");
        auditar("{\"n\":3}");
        auditar("{\"n\":4}");
        mvc.perform(post("/interno/v1/auditoria/anclas").header("X-Clave-Servicio", "clave-auditoria-prueba")
                .header("X-Correlation-Id", UUID.randomUUID().toString())).andExpect(status().isOk());
        long registros = contar("SELECT count(*) FROM auditoria.registros");

        Map<String, Long> borrados = jdbc.queryForList(
                        "SELECT * FROM infraestructura.borrar_rastro_empresas_ejemplo('PRUEBA')").stream()
                .collect(Collectors.toMap(f -> (String) f.get("objeto"), f -> ((Number) f.get("borrados")).longValue()));

        assertThat(borrados).containsEntry("casos", 1L).containsEntry("reportes", 1L)
                .containsEntry("cuentas de operador", 1L).containsEntry("sesiones", 1L)
                .containsEntry("entradas de historia de casos", 2L).containsEntry("eventos del outbox", 1L)
                .containsEntry("respuestas de idempotencia", 1L).containsEntry("registros de auditoría", 1L)
                .containsEntry("filas del registro de reasignación", 2L);
        // Lo de ejemplo ya no está.
        assertThat(contar("SELECT count(*) FROM catalogo.empresas WHERE id = ?::uuid", EJEMPLO)).isZero();
        assertThat(contar("SELECT count(*) FROM identidad.cuentas WHERE id = ?", operador)).isZero();
        assertThat(contar("SELECT count(*) FROM reportes.reportes WHERE id = ?", deEjemplo[0])).isZero();
        assertThat(contar("SELECT count(*) FROM indicadores.vista_reportes WHERE id_reporte = ?", deEjemplo[0])).isZero();
        assertThat(contar("SELECT count(*) FROM atencion.historia_caso WHERE autor = ?", operador)).isZero();
        assertThat(contar("SELECT count(*) FROM auditoria.registros WHERE detalle::text LIKE ?", "%" + EJEMPLO + "%"))
                .isZero();
        // Lo ajeno se conserva.
        assertThat(contar("SELECT count(*) FROM atencion.casos WHERE id = ?", ajeno[1])).isOne();
        assertThat(contar("SELECT count(*) FROM atencion.historia_caso WHERE id_caso = ?", ajeno[1])).isOne();
        assertThat(contar("SELECT count(*) FROM identidad.cuentas WHERE id = ?", operadorEnel)).isOne();
        assertThat(contar("SELECT count(*) FROM infraestructura.outbox WHERE sobre::text LIKE ?", "%" + ajeno[1] + "%"))
                .isOne();
        assertThat(contar("SELECT count(*) FROM auditoria.registros")).isEqualTo(registros - 1);

        // La cadena quedó íntegra y el ancla que cubría el tramo recalculado figura como reemplazada.
        mvc.perform(get("/interno/v1/auditoria/integridad").header("X-Clave-Servicio", "clave-auditoria-prueba")
                        .header("X-Correlation-Id", UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.integra").value(true));
        mvc.perform(post("/interno/v1/auditoria/anclas/verificacion").header("X-Clave-Servicio", "clave-auditoria-prueba")
                        .header("X-Correlation-Id", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coinciden").value(true))
                .andExpect(jsonPath("$.distintas.length()").value(0))
                .andExpect(jsonPath("$.reemplazadas.length()").value(1))
                .andExpect(jsonPath("$.reemplazadas[0].ubicacion").value(
                        org.hamcrest.Matchers.containsString("reencadenamiento PRUEBA")));
        assertThat(contar("SELECT count(*) FROM auditoria.reencadenamientos WHERE migracion = 'PRUEBA'")).isOne();

        // Volver a correrla no borra nada más.
        assertThat(jdbc.queryForList("SELECT * FROM infraestructura.borrar_rastro_empresas_ejemplo('PRUEBA-2')"))
                .allMatch(f -> ((Number) f.get("borrados")).longValue() == 0);
    }
}
