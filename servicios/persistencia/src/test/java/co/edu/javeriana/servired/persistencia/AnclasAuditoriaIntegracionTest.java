package co.edu.javeriana.servired.persistencia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.javeriana.servired.persistencia.aplicacion.AnclasAuditoria;
import co.edu.javeriana.servired.persistencia.aplicacion.ArchivoAuditoria;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.CodificadorSegmentos;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.RegistroArchivado;
import co.edu.javeriana.servired.persistencia.dominio.ServicioCliente;
import co.edu.javeriana.servired.persistencia.infraestructura.almacen.AlmacenS3;
import co.edu.javeriana.servired.persistencia.soporte.AlmacenDePrueba;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
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
 * Anclas externas de la cadena de auditoría (ADR-31), con GitHub simulado por un servidor HTTP
 * local. El caso central: alguien con acceso a la base y al bucket reescribe un segmento y
 * recalcula toda la cadena; la verificación interna no lo nota, las anclas publicadas sí.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {"servired.claves.auditoria=clave-auditoria-prueba",
        "servired.auditoria.archivo.activo=false", "servired.auditoria.anclas.activo=false",
        "servired.auditoria.anclas.github.repositorio=dueno/repo",
        "servired.auditoria.anclas.github.token=token-de-prueba"})
class AnclasAuditoriaIntegracionTest {

    // ------------------------------------------------------------- GitHub simulado
    static final Map<String, String> RAMAS = new ConcurrentHashMap<>();    // rama -> contenido del archivo
    static final AtomicInteger COMMITS = new AtomicInteger();
    static final Map<String, String> AUTORIZACION = new ConcurrentHashMap<>();
    static final HttpServer GITHUB = iniciar();

    static HttpServer iniciar() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            s.createContext("/", x -> {
                String ruta = x.getRequestURI().getPath();
                String metodo = x.getRequestMethod();
                AUTORIZACION.put("ultima", String.valueOf(x.getRequestHeaders().getFirst("Authorization")));
                String cuerpo = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                int estado = 404;
                String respuesta = "{}";
                if (ruta.equals("/repos/dueno/repo")) {
                    estado = 200;
                    respuesta = "{\"default_branch\":\"main\"}";
                } else if (ruta.equals("/repos/dueno/repo/branches/anclas-auditoria")) {
                    estado = RAMAS.containsKey("anclas-auditoria") ? 200 : 404;
                } else if (ruta.equals("/repos/dueno/repo/git/ref/heads/main")) {
                    estado = 200;
                    respuesta = "{\"object\":{\"sha\":\"abc123\"}}";
                } else if (ruta.equals("/repos/dueno/repo/git/refs") && metodo.equals("POST")) {
                    RAMAS.put("anclas-auditoria", "");
                    estado = 201;
                } else if (ruta.equals("/repos/dueno/repo/contents/auditoria/anclas.jsonl")) {
                    String contenido = RAMAS.get("anclas-auditoria");
                    if (metodo.equals("GET") && contenido != null && !contenido.isEmpty()) {
                        estado = 200;
                        respuesta = "{\"sha\":\"sha-" + COMMITS.get() + "\",\"html_url\":\"https://github.test/archivo\","
                                + "\"content\":\"" + Base64.getMimeEncoder().encodeToString(
                                        contenido.getBytes(StandardCharsets.UTF_8)).replace("\r\n", "\\n") + "\"}";
                    } else if (metodo.equals("PUT")) {
                        String b64 = cuerpo.replaceAll(".*\"content\":\"([^\"]+)\".*", "$1");
                        RAMAS.put("anclas-auditoria", new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8));
                        int n = COMMITS.incrementAndGet();
                        estado = 201;
                        respuesta = "{\"commit\":{\"sha\":\"c" + n + "\",\"html_url\":\"https://github.test/commit/c" + n + "\"}}";
                    }
                }
                byte[] b = respuesta.getBytes(StandardCharsets.UTF_8);
                x.getResponseHeaders().add("Content-Type", "application/json");
                x.sendResponseHeaders(estado, b.length);
                x.getResponseBody().write(b);
                x.close();
            });
            s.start();
            return s;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void detener() {
        GITHUB.stop(0);
    }

    // ------------------------------------------------------------- contenedores
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
        r.add("servired.auditoria.anclas.github.api", () -> "http://localhost:" + GITHUB.getAddress().getPort());
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AnclasAuditoria anclas;

    @Autowired
    ArchivoAuditoria archivo;

    @Autowired
    AlmacenS3 s3;

    @Autowired
    CodificadorSegmentos codificador;

    private void registrar(int n) throws Exception {
        for (int i = 0; i < n; i++) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                            "/interno/v1/auditoria/registros").header("X-Clave-Servicio", "clave-auditoria-prueba")
                    .header("X-Correlation-Id", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"idEvento":"%s","tipo":"accion.auditada","version":1,"origen":"identidad","categoria":"ACCESO",
                             "accion":"INICIO_SESION","resultado":"EXITO","detalle":{"n":%d},"ocurridoEn":"2026-11-17T10:00:00Z"}"""
                            .formatted(UUID.randomUUID(), i)));
        }
    }

    @Test
    @Order(1)
    void publicaElAnclaDelDiaEnGitHubUnaSolaVez() throws Exception {
        registrar(6);
        String ultimo = jdbc.queryForObject("SELECT hash FROM auditoria.registros ORDER BY secuencia DESC LIMIT 1",
                String.class);

        var ancla = anclas.anclar(ServicioCliente.AUDITORIA).orElseThrow();

        assertThat(ancla.hash()).isEqualTo(ultimo);
        assertThat(ancla.publicadaEn()).isNotNull();
        assertThat(ancla.referencia()).isEqualTo("https://github.test/commit/c1");
        assertThat(ancla.destino()).isEqualTo("github:dueno/repo@anclas-auditoria:auditoria/anclas.jsonl");
        assertThat(RAMAS.get("anclas-auditoria")).as("la rama se creó y el archivo tiene la línea del día")
                .contains("\"hash\":\"" + ultimo + "\"").contains("\"sistema\":\"SERVI-RED\"");
        assertThat(AUTORIZACION.get("ultima")).isEqualTo("Bearer token-de-prueba");

        registrar(2);
        anclas.anclar(ServicioCliente.AUDITORIA);
        assertThat(COMMITS.get()).as("una por día: no vuelve a publicar").isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("UPDATE auditoria.anclas SET hash = repeat('0', 64)"))
                .hasMessageContaining("inmutable");
    }

    @Test
    @Order(2)
    void laVerificacionUsaLoPublicadoAfueraYEncuentraElHashAunqueEsteArchivado() {
        archivo.archivar(Instant.now().plusSeconds(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auditoria.registros", Long.class)).isZero();

        var v = anclas.verificar(ServicioCliente.AUDITORIA);

        assertThat(v.fuente()).startsWith("github:");
        assertThat(v.anclas()).isEqualTo(1);
        assertThat(v.coinciden()).isTrue();
    }

    @Test
    @Order(3)
    void unaReescrituraCompletaQueEngañaALaVerificacionInternaChocaConElAncla() throws Exception {
        long anclada = jdbc.queryForObject("SELECT secuencia FROM auditoria.anclas", Long.class);
        String clave = jdbc.queryForObject("SELECT clave_objeto FROM auditoria.segmentos WHERE ? BETWEEN secuencia_desde "
                + "AND secuencia_hasta", String.class, anclada);
        var filas = new ArrayList<>(codificador.decodificar(s3.leer(clave).orElseThrow()));

        // El atacante altera el segundo registro y recalcula todas las huellas siguientes con la misma función.
        String anterior = filas.get(0).hash();
        for (int i = 1; i < filas.size(); i++) {
            var r = filas.get(i);
            String accion = i == 1 ? "BORRADO_DE_EVIDENCIA" : r.accion();
            String hash = jdbc.queryForObject("""
                    SELECT auditoria.huella(?, ?::uuid, ?, ?, ?, ?, ?, ?::uuid, ?, ?, ?, ?::jsonb, ?::timestamptz)""",
                    String.class, anterior, r.idEvento(), r.tipo(), r.origen(), r.categoria(), accion, r.resultado(),
                    r.actorId(), r.actorRol(), r.recursoTipo(), r.recursoId(), r.detalle(), r.ocurridoEn());
            filas.set(i, new RegistroArchivado(r.secuencia(), r.idEvento(), r.tipo(), r.version(), r.origen(),
                    r.correlationId(), r.categoria(), accion, r.resultado(), r.actorId(), r.actorRol(), r.recursoTipo(),
                    r.recursoId(), r.detalle(), r.ocurridoEn(), r.registradoEn(), anterior, hash));
            anterior = hash;
        }
        byte[] nuevo = codificador.codificar(filas);
        s3.guardar(clave, "application/gzip", nuevo);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(nuevo));
        String hashFinal = anterior;
        // ...y, con acceso a la base, desactiva los triggers y ajusta el índice del segmento.
        jdbc.execute((ConnectionCallback<Object>) c -> {
            try (var s = c.createStatement()) {
                s.execute("SET session_replication_role = replica");
                s.execute("UPDATE auditoria.segmentos SET sha256_archivo = '" + sha + "', bytes = " + nuevo.length
                        + ", hash_final = '" + hashFinal + "' WHERE clave_objeto = '" + clave + "'");
                s.execute("SET session_replication_role = DEFAULT");
            }
            return null;
        });

        assertThat(archivo.verificar(ServicioCliente.AUDITORIA).integro())
                .as("la verificación interna no lo nota: todo es coherente entre sí").isTrue();
        var v = anclas.verificar(ServicioCliente.AUDITORIA);
        assertThat(v.coinciden()).as("el ancla publicada en GitHub sí").isFalse();
        var distinta = v.distintas().getFirst();
        assertThat(distinta.secuencia()).isEqualTo(anclada);
        assertThat(distinta.hashActual()).as("el hash que hoy tiene esa secuencia, ya reescrito")
                .isEqualTo(filas.stream().filter(f -> f.secuencia() == anclada).findFirst().orElseThrow().hash())
                .isNotEqualTo(distinta.hashPublicado());
        assertThat(distinta.ubicacion()).startsWith("segmento");
    }

    @Test
    @Order(4)
    void conLaCadenaRotaNoSeAnclaUnEstadoAlterado() throws Exception {
        registrar(1);
        jdbc.execute((ConnectionCallback<Object>) c -> {
            try (var s = c.createStatement()) {
                s.execute("SET session_replication_role = replica");
                s.execute("UPDATE auditoria.registros SET accion = 'X'");
                s.execute("SET session_replication_role = DEFAULT");
            }
            return null;
        });
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/interno/v1/auditoria/anclas")
                        .header("X-Clave-Servicio", "clave-auditoria-prueba")
                        .header("X-Correlation-Id", UUID.randomUUID().toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
    }
}
