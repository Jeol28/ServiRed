package co.edu.javeriana.servired.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Seguridad del gateway de punta a punta: tokens RS256 reales firmados en la
 * prueba, JWKS y servicios de destino simulados con un servidor HTTP local.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "servired.limites.autenticacion-por-minuto=1000",
                "servired.limites.denuncias-por-minuto=4"})
@AutoConfigureWebTestClient
class GatewaySeguridadTest {

    static final RSAKey CLAVE = generar();
    static final RSAKey OTRA_CLAVE = generar();
    static final Map<String, String> AUTORIZACION_RECIBIDA = new ConcurrentHashMap<>();
    static final HttpServer DESTINO = iniciarDestino();

    @Autowired
    WebTestClient cliente;

    static RSAKey generar() {
        try {
            return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Sirve el JWKS y responde 200 a todo, anotando si llegó Authorization. */
    static HttpServer iniciarDestino() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            s.createContext("/", x -> {
                String ruta = x.getRequestURI().getPath();
                byte[] cuerpo;
                if (ruta.equals("/.well-known/jwks.json")) {
                    cuerpo = new JWKSet(CLAVE.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                } else {
                    String auth = x.getRequestHeaders().getFirst("Authorization");
                    AUTORIZACION_RECIBIDA.put(ruta, auth == null ? "<ninguna>" : auth);
                    cuerpo = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                }
                x.getResponseHeaders().add("Content-Type", "application/json");
                x.sendResponseHeaders(200, cuerpo.length);
                x.getResponseBody().write(cuerpo);
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
        DESTINO.stop(0);
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry r) {
        String base = "http://localhost:" + DESTINO.getAddress().getPort();
        r.add("servired.jwt.jwks-url", () -> base + "/.well-known/jwks.json");
        for (String s : new String[]{"IDENTIDAD", "REPORTES", "ATENCION", "INDICADORES"}) {
            r.add("SERVICIO_" + s + "_URL", () -> base);
        }
    }

    static String token(String rol, RSAKey clave, String emisor, String audiencia, Instant expira) {
        try {
            var claims = new JWTClaimsSet.Builder().issuer(emisor).audience(audiencia)
                    .subject(UUID.randomUUID().toString()).claim("rol", rol)
                    .issueTime(new Date()).expirationTime(Date.from(expira)).build();
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(clave.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(clave));
            return "Bearer " + jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String token(String rol) {
        return token(rol, CLAVE, "servired-identidad", "servired-api", Instant.now().plusSeconds(600));
    }

    private WebTestClient.ResponseSpec get(String ruta, String autorizacion) {
        var p = cliente.get().uri(ruta);
        if (autorizacion != null) {
            p = p.header("Authorization", autorizacion);
        }
        return p.exchange();
    }

    @Test
    void lasRutasProtegidasExigenToken() {
        get("/api/v1/reportes/x", null).expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.correlationId").exists();
    }

    @Test
    void unCiudadanoAccedeASusReportesConUnTokenValido() {
        get("/api/v1/reportes/x", token("CIUDADANO")).expectStatus().isOk();
        assertThat(AUTORIZACION_RECIBIDA.get("/api/v1/reportes/x")).startsWith("Bearer ");
    }

    @Test
    void rechazaTokensConFirmaEmisorAudienciaOExpiracionInvalidos() {
        Instant luego = Instant.now().plusSeconds(600);
        get("/api/v1/reportes/x", token("CIUDADANO", OTRA_CLAVE, "servired-identidad", "servired-api", luego))
                .expectStatus().isUnauthorized();
        get("/api/v1/reportes/x", token("CIUDADANO", CLAVE, "otro-emisor", "servired-api", luego))
                .expectStatus().isUnauthorized();
        get("/api/v1/reportes/x", token("CIUDADANO", CLAVE, "servired-identidad", "otra-api", luego))
                .expectStatus().isUnauthorized();
        get("/api/v1/reportes/x", token("CIUDADANO", CLAVE, "servired-identidad", "servired-api",
                Instant.now().minusSeconds(3600))).expectStatus().isUnauthorized();
    }

    @Test
    void aplicaMinimoPrivilegioPorRol() {
        get("/api/v1/reportes/x", token("OPERADOR")).expectStatus().isForbidden();
        get("/api/v1/casos", token("CIUDADANO")).expectStatus().isForbidden();
        get("/api/v1/casos", token("REGULADOR")).expectStatus().isOk();
        cliente.patch().uri("/api/v1/casos/x").header("Authorization", token("REGULADOR")).exchange()
                .expectStatus().isForbidden();
        cliente.patch().uri("/api/v1/casos/x").header("Authorization", token("OPERADOR")).exchange()
                .expectStatus().isOk();
        get("/api/v1/moderacion/denuncias", token("REGULADOR")).expectStatus().isOk();
        cliente.post().uri("/api/v1/moderacion/denuncias/x/decision").header("Authorization", token("REGULADOR"))
                .exchange().expectStatus().isForbidden();
        cliente.post().uri("/api/v1/moderacion/denuncias/x/decision").header("Authorization", token("OPERADOR"))
                .exchange().expectStatus().isOk();
        get("/api/v1/moderacion/denuncias", token("CIUDADANO")).expectStatus().isForbidden();
        get("/api/v1/cuentas", token("REGULADOR")).expectStatus().isForbidden();
        get("/api/v1/cuentas", token("ADMINISTRADOR")).expectStatus().isOk();
        get("/api/v1/cuentas/yo", token("OPERADOR")).expectStatus().isOk();
    }

    @Test
    void elPortalPublicoYLaAutenticacionNoRequierenToken() {
        get("/api/v1/indicadores/resumen", null).expectStatus().isOk();
        get("/api/v1/datos-abiertos/x", null).expectStatus().isOk();
        cliente.post().uri("/api/v1/auth/login").exchange().expectStatus().isOk();
        cliente.post().uri("/api/v1/indicadores/x").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void laCorrelacionDeUnaDenunciaNuncaVieneDelCliente() {
        String delCliente = UUID.randomUUID().toString();

        String devuelto = cliente.post().uri("/api/v1/denuncias/x").header("X-Correlation-Id", delCliente).exchange()
                .returnResult(String.class).getResponseHeaders().getFirst("X-Correlation-Id");

        assertThat(devuelto).isNotEqualTo(delCliente);
        assertThat(UUID.fromString(devuelto)).isNotNull();
    }

    @Test
    void lasDenunciasLleganSinCredencialesYTienenLimiteDeAbuso() {
        String ruta = "/api/v1/denuncias";
        cliente.post().uri(ruta).header("Authorization", token("CIUDADANO")).exchange().expectStatus().isOk();
        assertThat(AUTORIZACION_RECIBIDA.get(ruta)).isEqualTo("<ninguna>");

        // El contador es compartido con otras pruebas: se envía hasta superar el límite (4/min).
        int limitadas = 0;
        for (int i = 0; i < 6; i++) {
            var r = cliente.post().uri(ruta).exchange().returnResult(String.class);
            if (r.getStatus().value() == 429) {
                limitadas++;
                assertThat(r.getResponseHeaders().getFirst("Retry-After")).isNotBlank();
                assertThat(r.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            }
        }
        assertThat(limitadas).isPositive();
    }
}
