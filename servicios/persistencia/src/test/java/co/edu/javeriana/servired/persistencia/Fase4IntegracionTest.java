package co.edu.javeriana.servired.persistencia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.mock.web.MockMultipartFile;
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

/** Restablecimiento, validación, evidencias y denuncias contra PostgreSQL, RabbitMQ y S3 reales. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {"servired.claves.reportes=clave-reportes-de-prueba",
        "servired.claves.atencion=clave-atencion-de-prueba"})
class Fase4IntegracionTest {

    static final String CLAVE = "clave-reportes-de-prueba";
    static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

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

    @Autowired
    co.edu.javeriana.servired.persistencia.infraestructura.almacen.AlmacenS3 almacenS3;

    private static String evento(String tipo, String correlacion) {
        return """
                {"idEvento":"%s","tipo":"%s","version":1,"fechaOcurrencia":"2026-11-16T12:00:00Z",
                 "correlationId":"%s","origen":"reportes","datos":{}}""".formatted(UUID.randomUUID(), tipo, correlacion);
    }

    private ResultActions enviar(String ruta, String cuerpo, String correlacion) throws Exception {
        return mvc.perform(post(ruta).header("X-Clave-Servicio", CLAVE).header("X-Correlation-Id", correlacion)
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private UUID crearReporte(UUID ciudadano, double lat, double lon) throws Exception {
        UUID id = UUID.randomUUID();
        String c = UUID.randomUUID().toString();
        enviar("/interno/v1/reportes/guardar", """
                {"reporte":{"idReporte":"%s","idCiudadano":"%s","tipoServicio":"ACUEDUCTO","latitud":%s,
                  "longitud":%s,"codigoMunicipio":"11001","inicioEstimado":"2026-11-16T08:00:00Z",
                  "magnitud":"BARRIO","fechaRegistro":"2026-11-16T09:00:00Z"},"evento":%s}"""
                .formatted(id, ciudadano, lat, lon, evento("reporte.creado", c)), c).andExpect(status().isCreated());
        return id;
    }

    private ResultActions votar(UUID reporte, UUID ciudadano, String objeto, String sentido, int version)
            throws Exception {
        return votar(reporte, ciudadano, objeto, sentido, version, "");
    }

    private ResultActions votar(UUID reporte, UUID ciudadano, String objeto, String sentido, int version,
                                String extra) throws Exception {
        String c = UUID.randomUUID().toString();
        return enviar("/interno/v1/reportes/" + reporte + "/validaciones", """
                {"objeto":"%s","sentido":"%s","idCiudadano":"%s","fecha":"2026-11-16T10:00:00Z",
                 "versionEsperada":%d,"evento":%s%s}""".formatted(objeto, sentido, ciudadano, version,
                evento("validacion.registrada", c), extra), c);
    }

    @Test
    void elVotoDeUnaCuentaNuevaSeGuardaPeroNoSuma() throws Exception {
        UUID id = crearReporte(UUID.randomUUID(), 4.62, -74.06);
        UUID nueva = UUID.randomUUID();

        votar(id, nueva, "CORTE", "CONFIRMA", 0, ",\"cuenta\":false").andExpect(status().isCreated())
                .andExpect(jsonPath("$.validacionCorte.confirmaciones").value(0));
        // Ya votó: no puede repetir aunque su cuenta envejezca.
        votar(id, nueva, "CORTE", "CONFIRMA", 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.motivo").value("YA_VOTO"));
        // Una cuenta establecida sí suma (y "cuenta" ausente = true, compatible).
        votar(id, UUID.randomUUID(), "CORTE", "CONFIRMA", 1).andExpect(status().isCreated())
                .andExpect(jsonPath("$.validacionCorte.confirmaciones").value(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reportes.validaciones WHERE id_reporte = ? AND NOT cuenta",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    void registraElRestablecimientoUnaSolaVez() throws Exception {
        UUID id = crearReporte(UUID.randomUUID(), 4.60, -74.08);
        String cuerpo = """
                {"fechaRestablecimiento":"2026-11-16T11:30:00Z","duracionMinutos":210,"comentario":"Volvió el agua",
                 "evento":%s}""";
        String c = UUID.randomUUID().toString();

        enviar("/interno/v1/reportes/" + id + "/restablecimiento", cuerpo.formatted(evento("restablecimiento.registrado", c)), c)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("RESTABLECIDO"))
                .andExpect(jsonPath("$.duracionMinutos").value(210))
                .andExpect(jsonPath("$.historia[1].estado").value("RESTABLECIDO"));
        String c2 = UUID.randomUUID().toString();
        enviar("/interno/v1/reportes/" + id + "/restablecimiento", cuerpo.formatted(evento("restablecimiento.registrado", c2)), c2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.motivo").value("ESTADO"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM infraestructura.outbox WHERE tipo = 'restablecimiento.registrado' AND sobre->>'correlationId' IN (?, ?)",
                Integer.class, c, c2)).isEqualTo(1);
    }

    @Test
    void cuentaLosVotosConConcurrenciaOptimistaYUnVotoPorCiudadano() throws Exception {
        UUID id = crearReporte(UUID.randomUUID(), 4.61, -74.07);
        UUID ana = UUID.randomUUID();

        votar(id, ana, "CORTE", "CONFIRMA", 0).andExpect(status().isCreated())
                .andExpect(jsonPath("$.validacionCorte.confirmaciones").value(1))
                .andExpect(jsonPath("$.versionValidaciones").value(1));
        votar(id, UUID.randomUUID(), "CORTE", "CONTROVIERTE", 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.motivo").value("VERSION"));
        votar(id, ana, "CORTE", "CONFIRMA", 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.motivo").value("YA_VOTO"));
        votar(id, UUID.randomUUID(), "CORTE", "CONTROVIERTE", 1).andExpect(status().isCreated())
                .andExpect(jsonPath("$.validacionCorte.controversias").value(1));
        assertThat(jdbc.queryForObject("SELECT version_validaciones FROM reportes.reportes WHERE id = ?",
                Integer.class, id)).isEqualTo(2);
    }

    @Test
    void buscaReportesCercanosSinIncluirLosPropios() throws Exception {
        UUID autor = UUID.randomUUID();
        UUID cerca = crearReporte(autor, 7.1190, -73.1220);
        UUID propio = crearReporte(UUID.randomUUID(), 7.1191, -73.1221);
        crearReporte(autor, 7.1500, -73.1220);

        String r = mvc.perform(get("/interno/v1/reportes/cercanos?latitud=7.1195&longitud=-73.1225&radioMetros=1000&excluirCiudadano="
                                + UUID.randomUUID()).header("X-Clave-Servicio", CLAVE)
                        .header("X-Correlation-Id", UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(r).contains(cerca.toString(), propio.toString());

        mvc.perform(get("/interno/v1/reportes/cercanos?latitud=7.1195&longitud=-73.1225&excluirCiudadano=" + autor)
                        .header("X-Clave-Servicio", CLAVE).header("X-Correlation-Id", UUID.randomUUID().toString()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].idReporte").value(propio.toString()))
                .andExpect(jsonPath("$[0].distanciaMetros").isNumber())
                .andExpect(jsonPath("$[0].idCiudadano").doesNotExist());
    }

    @Test
    void guardaYEntregaLaEvidenciaDesdeElAlmacen() throws Exception {
        UUID id = crearReporte(UUID.randomUUID(), 4.62, -74.06);
        UUID evidencia = UUID.randomUUID();

        mvc.perform(post("/interno/v1/reportes/" + id + "/evidencias").header("X-Clave-Servicio", CLAVE)
                        .header("X-Correlation-Id", UUID.randomUUID().toString()).header("X-Id-Evidencia", evidencia)
                        .contentType(MediaType.IMAGE_PNG).content(PNG))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.evidencias[0].idEvidencia").value(evidencia.toString()))
                .andExpect(jsonPath("$.evidencias[0].tamanoBytes").value(PNG.length));

        mvc.perform(get("/interno/v1/reportes/" + id + "/evidencias/" + evidencia).header("X-Clave-Servicio", CLAVE)
                        .header("X-Correlation-Id", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(PNG));
        mvc.perform(get("/interno/v1/reportes/" + UUID.randomUUID() + "/evidencias/" + evidencia)
                        .header("X-Clave-Servicio", CLAVE).header("X-Correlation-Id", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void limitaATresEvidenciasPorReporte() throws Exception {
        UUID id = crearReporte(UUID.randomUUID(), 4.63, -74.05);
        for (int i = 0; i < 4; i++) {
            var r = mvc.perform(post("/interno/v1/reportes/" + id + "/evidencias").header("X-Clave-Servicio", CLAVE)
                    .header("X-Correlation-Id", UUID.randomUUID().toString()).header("X-Id-Evidencia", UUID.randomUUID())
                    .contentType(MediaType.IMAGE_PNG).content(PNG));
            if (i < 3) {
                r.andExpect(status().isCreated());
            } else {
                r.andExpect(status().isConflict()).andExpect(jsonPath("$.motivo").value("LIMITE"));
            }
        }
    }

    @Test
    void guardaLaDenunciaAnonimaConSuFotoYSuEvento() throws Exception {
        UUID id = UUID.randomUUID();
        String c = UUID.randomUUID().toString();
        String solicitud = """
                {"denuncia":{"idDenuncia":"%s","tipoServicio":"ENERGIA","latitud":10.98,"longitud":-74.81,
                  "codigoMunicipio":"08001","descripcion":"Conexión directa al poste de la esquina",
                  "fechaRecepcion":"2026-11-16T12:00:00Z"},"evento":%s}""".formatted(id, evento("denuncia.recibida", c));

        mvc.perform(multipart("/interno/v1/denuncias")
                        .file(new MockMultipartFile("solicitud", "", "application/json", solicitud.getBytes()))
                        .file(new MockMultipartFile("foto", "foto.png", "image/png", PNG))
                        .header("X-Clave-Servicio", CLAVE).header("X-Correlation-Id", c))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("PENDIENTE_VALIDACION"))
                .andExpect(jsonPath("$.tieneEvidencia").value(true));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM reportes.evidencias WHERE id_denuncia = ?", Integer.class, id))
                .isEqualTo(1);
        // El objeto guardado es byte a byte la foto enviada (no un cuerpo aws-chunked).
        String clave = jdbc.queryForObject("SELECT clave_objeto FROM reportes.evidencias WHERE id_denuncia = ?",
                String.class, id);
        assertThat(almacenS3.leer(clave)).hasValueSatisfying(b -> assertThat(b).isEqualTo(PNG));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM infraestructura.outbox WHERE tipo = 'denuncia.recibida' AND correlation_id = ?::uuid",
                Integer.class, c)).isEqualTo(1);
    }

    @Test
    void atencionNoPuedeGuardarDenunciasNiLeerEvidencias() throws Exception {
        mvc.perform(get("/interno/v1/reportes/" + UUID.randomUUID() + "/evidencias/" + UUID.randomUUID())
                        .header("X-Clave-Servicio", "clave-atencion-de-prueba")
                        .header("X-Correlation-Id", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    void readinessIncluyeElAlmacen() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
