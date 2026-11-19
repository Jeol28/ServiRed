package co.edu.javeriana.servired.persistencia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.annotation.DirtiesContext;
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

/** Registro de auditoría encadenado y de solo inserción, contra PostgreSQL real. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext
@TestPropertySource(properties = {"servired.claves.auditoria=clave-auditoria-prueba",
        "servired.claves.identidad=clave-identidad-de-prueba", "servired.claves.atencion=clave-atencion-de-prueba"})
class AuditoriaIntegracionTest {

    static final String AUDITORIA = "clave-auditoria-prueba";
    static final String IDENTIDAD = "clave-identidad-de-prueba";

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

    private ResultActions enviar(String clave, String ruta, String cuerpo, String correlacion) throws Exception {
        return mvc.perform(post(ruta).header("X-Clave-Servicio", clave).header("X-Correlation-Id", correlacion)
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private ResultActions registrar(UUID idEvento, String accion) throws Exception {
        return enviar(AUDITORIA, "/interno/v1/auditoria/registros", """
                {"idEvento":"%s","tipo":"accion.auditada","version":1,"origen":"identidad","categoria":"ACCESO",
                 "accion":"%s","resultado":"FALLO","recursoTipo":"CUENTA","recursoId":"%s",
                 "detalle":{"motivo":"clave incorrecta"},"ocurridoEn":"2026-11-17T10:00:00Z"}"""
                .formatted(idEvento, accion, UUID.randomUUID()), UUID.randomUUID().toString());
    }

    private ResultActions verificar() throws Exception {
        return mvc.perform(get("/interno/v1/auditoria/integridad").header("X-Clave-Servicio", AUDITORIA)
                .header("X-Correlation-Id", UUID.randomUUID().toString()));
    }

    @Test
    void encadenaLosRegistrosDeFormaIdempotenteYVerificaLaCadena() throws Exception {
        UUID primero = UUID.randomUUID();
        registrar(primero, "INICIO_SESION_FALLIDO").andExpect(status().isCreated());
        registrar(primero, "INICIO_SESION_FALLIDO").andExpect(status().isOk()).andExpect(jsonPath("$.nuevo").value(false));
        registrar(UUID.randomUUID(), "INICIO_SESION").andExpect(status().isCreated());

        String anterior = jdbc.queryForObject("SELECT hash FROM auditoria.registros WHERE id_evento = ?", String.class, primero);
        assertThat(jdbc.queryForObject("SELECT hash_anterior FROM auditoria.registros ORDER BY secuencia DESC LIMIT 1",
                String.class)).isEqualTo(anterior);
        verificar().andExpect(jsonPath("$.integra").value(true));
        mvc.perform(get("/interno/v1/auditoria/registros?categoria=ACCESO&accion=INICIO_SESION")
                        .header("X-Clave-Servicio", AUDITORIA).header("X-Correlation-Id", UUID.randomUUID().toString()))
                .andExpect(jsonPath("$.elementos[0].accion").value("INICIO_SESION"));
    }

    @Test
    void esDeSoloInsercionYDetectaLaManipulacion() throws Exception {
        UUID id = UUID.randomUUID();
        registrar(id, "CUENTA_ELIMINADA").andExpect(status().isCreated());
        registrar(UUID.randomUUID(), "CUENTA_CREADA").andExpect(status().isCreated());

        assertThatThrownBy(() -> jdbc.update("UPDATE auditoria.registros SET accion = 'NADA' WHERE id_evento = ?", id))
                .hasMessageContaining("solo inserción");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM auditoria.registros WHERE id_evento = ?", id))
                .hasMessageContaining("solo inserción");

        // Un atacante con acceso directo desactiva el trigger y altera un registro: la cadena lo delata.
        jdbc.execute("ALTER TABLE auditoria.registros DISABLE TRIGGER registros_solo_insercion");
        jdbc.update("UPDATE auditoria.registros SET accion = 'MANIPULADA' WHERE id_evento = ?", id);
        jdbc.execute("ALTER TABLE auditoria.registros ENABLE TRIGGER registros_solo_insercion");
        long secuencia = jdbc.queryForObject("SELECT secuencia FROM auditoria.registros WHERE id_evento = ?", Long.class, id);

        verificar().andExpect(jsonPath("$.integra").value(false))
                .andExpect(jsonPath("$.primeraRota").value(secuencia));

        // Se restaura el valor original: la cadena vuelve a ser íntegra (y no afecta a las demás pruebas).
        jdbc.execute("ALTER TABLE auditoria.registros DISABLE TRIGGER registros_solo_insercion");
        jdbc.update("UPDATE auditoria.registros SET accion = 'CUENTA_ELIMINADA' WHERE id_evento = ?", id);
        jdbc.execute("ALTER TABLE auditoria.registros ENABLE TRIGGER registros_solo_insercion");
        verificar().andExpect(jsonPath("$.integra").value(true));
    }

    @Test
    void lasAccionesAuditadasSalenPorElOutboxYSoloAuditoriaRegistra() throws Exception {
        String c = UUID.randomUUID().toString();
        enviar(IDENTIDAD, "/interno/v1/acciones-auditadas", """
                {"idEvento":"%s","tipo":"accion.auditada","version":1,"fechaOcurrencia":"2026-11-17T10:00:00Z",
                 "correlationId":"%s","origen":"identidad","datos":{"categoria":"ACCESO","accion":"INICIO_SESION"}}"""
                .formatted(UUID.randomUUID(), c), c).andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM infraestructura.outbox WHERE correlation_id = ?::uuid",
                Integer.class, c)).isEqualTo(1);

        enviar(IDENTIDAD, "/interno/v1/acciones-auditadas", """
                {"idEvento":"%s","tipo":"accion.auditada","version":1,"fechaOcurrencia":"2026-11-17T10:00:00Z",
                 "correlationId":"%s","origen":"atencion","datos":{}}""".formatted(UUID.randomUUID(), c), c)
                .andExpect(status().isForbidden());
        enviar("clave-atencion-de-prueba", "/interno/v1/auditoria/registros", """
                {"idEvento":"%s","tipo":"x","version":1,"origen":"atencion","categoria":"ACCESO","accion":"X",
                 "ocurridoEn":"2026-11-17T10:00:00Z"}""".formatted(UUID.randomUUID()), c).andExpect(status().isForbidden());
    }

    @Test
    void crearUnaCuentaDejaSuAccionAuditadaEnLaMismaTransaccion() throws Exception {
        String c = UUID.randomUUID().toString();
        enviar(IDENTIDAD, "/interno/v1/cuentas/registrar", """
                {"idCuenta":"%s","correo":"%s@ejemplo.co","hashClave":"h","rol":"REGULADOR","creadaEn":"2026-11-17T10:00:00Z",
                 "eventos":[{"idEvento":"%s","tipo":"accion.auditada","version":1,"fechaOcurrencia":"2026-11-17T10:00:00Z",
                  "correlationId":"%s","origen":"identidad","datos":{"categoria":"ADMINISTRACION","accion":"CUENTA_CREADA"}}]}"""
                .formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), c), c).andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("SELECT tipo FROM infraestructura.outbox WHERE correlation_id = ?::uuid",
                String.class, c)).isEqualTo("accion.auditada");
    }
}
