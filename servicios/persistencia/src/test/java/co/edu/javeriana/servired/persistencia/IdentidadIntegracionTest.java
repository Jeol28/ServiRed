package co.edu.javeriana.servired.persistencia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Operaciones de cuentas y sesiones (dominio de Identidad) contra PostgreSQL real. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "servired.claves.identidad=clave-identidad-de-prueba",
        "servired.claves.reportes=clave-reportes-de-prueba"})
class IdentidadIntegracionTest {

    static final String CLAVE = "clave-identidad-de-prueba";
    static final String EMPRESA = "a0000000-0000-4000-8000-000000000001";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

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
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private ResultActions llamar(String ruta, String cuerpo, String correlacion) throws Exception {
        return mvc.perform(post(ruta).header("X-Clave-Servicio", CLAVE).header("X-Correlation-Id", correlacion)
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private ResultActions llamar(String ruta, String cuerpo) throws Exception {
        return llamar(ruta, cuerpo, UUID.randomUUID().toString());
    }

    private static String cuenta(UUID id, String correo, String rol, String empresa) {
        return """
                {"idCuenta":"%s","correo":"%s","hashClave":"$2a$10$hash","rol":"%s",%s"nombre":"Ana",
                 "consentimientoDatosEn":"2026-11-16T10:00:00Z","creadaEn":"2026-11-16T10:00:00Z"}"""
                .formatted(id, correo, rol, empresa == null ? "" : "\"idEmpresa\":\"" + empresa + "\",");
    }

    private static String correo() {
        return "persona" + UUID.randomUUID().toString().substring(0, 8) + "@ejemplo.co";
    }

    private static String hash() {
        byte[] b = new byte[32];
        ThreadLocalRandom.current().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private UUID registrar(String rol, String empresa) throws Exception {
        UUID id = UUID.randomUUID();
        llamar("/interno/v1/cuentas/registrar", cuenta(id, correo(), rol, empresa)).andExpect(status().isCreated());
        return id;
    }

    private String abrirSesion(UUID idCuenta) throws Exception {
        String h = hash();
        llamar("/interno/v1/sesiones/abrir", """
                {"idSesion":"%s","idCuenta":"%s","hashRefresco":"%s","expiraEn":"%s"}"""
                .formatted(UUID.randomUUID(), idCuenta, h, Instant.now().plus(1, ChronoUnit.DAYS)))
                .andExpect(status().isCreated());
        return h;
    }

    private ResultActions rotar(String actual, String nuevo) throws Exception {
        return llamar("/interno/v1/sesiones/rotar", """
                {"hashRefrescoActual":"%s","idSesionNueva":"%s","hashRefrescoNuevo":"%s","expiraEn":"%s"}"""
                .formatted(actual, UUID.randomUUID(), nuevo, Instant.now().plus(1, ChronoUnit.DAYS)));
    }

    private int sesionesActivas(UUID idCuenta) {
        return jdbc.queryForObject("SELECT count(*) FROM identidad.sesiones WHERE id_cuenta = ? AND revocada_en IS NULL",
                Integer.class, idCuenta);
    }

    @Test
    void registraUnaCuentaDeFormaIdempotenteYRechazaCorreosRepetidos() throws Exception {
        UUID id = UUID.randomUUID();
        String c = correo();
        String correlacion = UUID.randomUUID().toString();

        llamar("/interno/v1/cuentas/registrar", cuenta(id, c, "CIUDADANO", null), correlacion)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idCuenta").value(id.toString()))
                .andExpect(jsonPath("$.activa").value(true))
                .andExpect(jsonPath("$.hashClave").doesNotExist());
        llamar("/interno/v1/cuentas/registrar", cuenta(UUID.randomUUID(), c, "CIUDADANO", null), correlacion)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idCuenta").value(id.toString()));
        llamar("/interno/v1/cuentas/registrar", cuenta(UUID.randomUUID(), c, "CIUDADANO", null))
                .andExpect(status().isConflict());
    }

    @Test
    void unOperadorDebeTenerEmpresaYLosDemasNo() throws Exception {
        llamar("/interno/v1/cuentas/registrar", cuenta(UUID.randomUUID(), correo(), "OPERADOR", null))
                .andExpect(status().isConflict());
        llamar("/interno/v1/cuentas/registrar", cuenta(UUID.randomUUID(), correo(), "CIUDADANO", EMPRESA))
                .andExpect(status().isConflict());
        registrar("OPERADOR", EMPRESA);
    }

    @Test
    void entregaCredencialesSoloPorCorreoEnElCuerpo() throws Exception {
        UUID id = UUID.randomUUID();
        String c = correo();
        llamar("/interno/v1/cuentas/registrar", cuenta(id, c, "REGULADOR", null)).andExpect(status().isCreated());

        llamar("/interno/v1/cuentas/credenciales", "{\"correo\":\"" + c + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idCuenta").value(id.toString()))
                .andExpect(jsonPath("$.hashClave").value("$2a$10$hash"))
                .andExpect(jsonPath("$.rol").value("REGULADOR"));
        llamar("/interno/v1/cuentas/credenciales", "{\"correo\":\"nadie@ejemplo.co\"}")
                .andExpect(status().isNotFound());
    }

    @Test
    void soloIdentidadPuedeOperarCuentas() throws Exception {
        mvc.perform(post("/interno/v1/cuentas/credenciales").header("X-Clave-Servicio", "clave-reportes-de-prueba")
                        .header("X-Correlation-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"correo\":\"a@b.co\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listaPorRol() throws Exception {
        registrar("ADMINISTRADOR", null);

        mvc.perform(get("/interno/v1/cuentas?rol=ADMINISTRADOR&tamano=5").header("X-Clave-Servicio", CLAVE)
                        .header("X-Correlation-Id", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.elementos[0].rol").value("ADMINISTRADOR"))
                .andExpect(jsonPath("$.total").isNumber());
    }

    @Test
    void rotaLaSesionUnaSolaVezYDetectaLaReutilizacion() throws Exception {
        UUID id = registrar("CIUDADANO", null);
        String primero = abrirSesion(id);
        String segundo = hash();

        rotar(primero, segundo).andExpect(status().isOk()).andExpect(jsonPath("$.cuenta.idCuenta").value(id.toString()));
        assertThat(sesionesActivas(id)).isEqualTo(1);

        rotar(primero, hash()).andExpect(status().isConflict()).andExpect(jsonPath("$.motivo").value("REUTILIZADA"));
        assertThat(sesionesActivas(id)).isZero();
        rotar(segundo, hash()).andExpect(status().isConflict());
    }

    @Test
    void cerrarSesionLaRevoca() throws Exception {
        UUID id = registrar("CIUDADANO", null);
        String h = abrirSesion(id);

        llamar("/interno/v1/sesiones/cerrar", "{\"hashRefresco\":\"" + h + "\"}").andExpect(status().isNoContent());

        assertThat(sesionesActivas(id)).isZero();
    }

    @Test
    void desactivarUnaCuentaRevocaSusSesiones() throws Exception {
        UUID id = registrar("CIUDADANO", null);
        String h = abrirSesion(id);

        llamar("/interno/v1/cuentas/" + id + "/actualizar", "{\"activa\":false}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.activa").value(false));

        assertThat(sesionesActivas(id)).isZero();
        rotar(h, hash()).andExpect(status().isConflict());
    }

    @Test
    void eliminarAnonimizaLosDatosPersonales() throws Exception {
        UUID id = registrar("CIUDADANO", null);
        abrirSesion(id);

        llamar("/interno/v1/cuentas/" + id + "/eliminar", "{}").andExpect(status().isNoContent());

        var fila = jdbc.queryForMap("SELECT correo, nombre, hash_clave, activa FROM identidad.cuentas WHERE id = ?", id);
        assertThat(fila.get("correo")).isEqualTo("eliminada+" + id + "@servired.invalid");
        assertThat(fila.get("nombre")).isNull();
        assertThat(fila.get("hash_clave")).isEqualTo("!");
        assertThat(sesionesActivas(id)).isZero();
        mvc.perform(get("/interno/v1/cuentas/" + id).header("X-Clave-Servicio", CLAVE)
                .header("X-Correlation-Id", UUID.randomUUID().toString())).andExpect(status().isNotFound());
        llamar("/interno/v1/cuentas/" + id + "/eliminar", "{}").andExpect(status().isNotFound());
    }
}
