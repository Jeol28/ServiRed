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
import org.springframework.boot.test.web.server.LocalServerPort;
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
                "servired.limites.denuncias-por-minuto=4",
                "servired.limites.por-cuenta.reportes-por-minuto=3", "servired.limites.por-cuenta.reportes-por-hora=5",
                // La prueba se conecta desde localhost: hace de proxy de los portales.
                "servired.limites.proxies-confiables=localhost"})
@AutoConfigureWebTestClient
class GatewaySeguridadTest {

    static final RSAKey CLAVE = generar();
    static final RSAKey OTRA_CLAVE = generar();
    static final Map<String, String> AUTORIZACION_RECIBIDA = new ConcurrentHashMap<>();
    static final Map<String, String> REENVIADO_RECIBIDO = new ConcurrentHashMap<>();
    /** jti que Identidad informa como revocado (ADR-26). */
    static final String JTI_REVOCADO = UUID.randomUUID().toString();
    static final HttpServer DESTINO = iniciarDestino();

    @Autowired
    WebTestClient cliente;

    @LocalServerPort
    int puerto;

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
                } else if (ruta.equals("/interno/v1/tokens-revocados")) {
                    cuerpo = ("{\"generadaEn\":\"" + Instant.now() + "\",\"revocados\":[{\"jti\":\"" + JTI_REVOCADO
                            + "\",\"expiraEn\":\"" + Instant.now().plusSeconds(900) + "\"}]}")
                            .getBytes(StandardCharsets.UTF_8);
                } else {
                    String auth = x.getRequestHeaders().getFirst("Authorization");
                    AUTORIZACION_RECIBIDA.put(ruta, auth == null ? "<ninguna>" : auth);
                    var h = x.getRequestHeaders();
                    REENVIADO_RECIBIDO.put(ruta, String.join("|", String.valueOf(h.getFirst("X-Forwarded-For")),
                            String.valueOf(h.getFirst("X-Real-IP")), String.valueOf(h.getFirst("Forwarded"))));
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
        r.add("servired.jwt.revocacion.url", () -> base + "/interno/v1/tokens-revocados");
        for (String s : new String[]{"IDENTIDAD", "REPORTES", "ATENCION", "INDICADORES", "AUDITORIA"}) {
            r.add("SERVICIO_" + s + "_URL", () -> base);
        }
    }

    static String token(String rol, RSAKey clave, String emisor, String audiencia, Instant expira) {
        return token(rol, clave, emisor, audiencia, expira, UUID.randomUUID().toString());
    }

    static String token(String rol, RSAKey clave, String emisor, String audiencia, Instant expira, String jti) {
        try {
            var claims = new JWTClaimsSet.Builder().issuer(emisor).audience(audiencia).jwtID(jti)
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
    void unTokenRevocadoAntesDeExpirarRecibe401() throws InterruptedException {
        String revocado = token("CIUDADANO", CLAVE, "servired-identidad", "servired-api", Instant.now().plusSeconds(600),
                JTI_REVOCADO);
        // La primera sincronización corre al arrancar, en segundo plano.
        int estado = 0;
        for (int i = 0; i < 50 && estado != 401; i++) {
            estado = get("/api/v1/reportes/mios", revocado).returnResult(String.class).getStatus().value();
            Thread.sleep(100);
        }
        get("/api/v1/reportes/mios", revocado).expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON);
        get("/api/v1/reportes/mios", token("CIUDADANO")).expectStatus().isOk();
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
        // Catálogo de empresas (ADR-35): personal sí, ciudadano no.
        get("/api/v1/empresas", token("OPERADOR")).expectStatus().isOk();
        get("/api/v1/empresas", token("REGULADOR")).expectStatus().isOk();
        get("/api/v1/empresas", token("CIUDADANO")).expectStatus().isForbidden();
        get("/api/v1/auditoria/registros", token("REGULADOR")).expectStatus().isOk();
        get("/api/v1/auditoria/registros", token("OPERADOR")).expectStatus().isForbidden();
        get("/api/v1/auditoria/registros", null).expectStatus().isUnauthorized();
        get("/api/v1/auditoria/anclas", null).expectStatus().isOk();   // ADR-31: públicas
        get("/api/v1/auditoria/anclas/otra", null).expectStatus().isUnauthorized();
        cliente.post().uri("/api/v1/auditoria/registros").header("Authorization", token("ADMINISTRADOR")).exchange()
                .expectStatus().isForbidden();
        get("/interno/v1/alertas", token("ADMINISTRADOR")).expectStatus().isForbidden();
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
        get("/api/v1/mapas/publico", null).expectStatus().isOk();
        get("/api/v1/mapas/operativo", null).expectStatus().isUnauthorized();
        get("/api/v1/mapas/operativo", token("CIUDADANO")).expectStatus().isForbidden();
        get("/api/v1/mapas/operativo", token("OPERADOR")).expectStatus().isOk();
        get("/api/v1/indicadores/exportaciones/reportes", null).expectStatus().isUnauthorized();
        get("/api/v1/indicadores/exportaciones/reportes", token("OPERADOR")).expectStatus().isForbidden();
        get("/api/v1/indicadores/exportaciones/reportes", token("REGULADOR")).expectStatus().isOk();
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

    @Test
    void detrasDeUnProxyConfiableElLimiteEsPorClienteYLaIpNoLlegaALosServicios() {
        String ruta = "/api/v1/denuncias/proxy";
        // Conexión TCP real desde localhost (el cliente enlazado al contexto no tiene dirección remota).
        var cliente = WebTestClient.bindToServer().baseUrl("http://localhost:" + puerto).build();
        // Un cliente detrás del proxy agota su cupo (4/min)...
        int limitadas = 0;
        for (int i = 0; i < 5; i++) {
            limitadas += cliente.post().uri(ruta).header("X-Forwarded-For", "203.0.113.7").exchange()
                    .returnResult(String.class).getStatus().value() == 429 ? 1 : 0;
        }
        assertThat(limitadas).isEqualTo(1);
        // ...sin afectar a otro cliente que llega por el mismo proxy.
        cliente.post().uri(ruta).header("X-Forwarded-For", "203.0.113.8").header("X-Real-IP", "203.0.113.8")
                .header("Forwarded", "for=203.0.113.8").exchange().expectStatus().isOk();
        assertThat(REENVIADO_RECIBIDO.get(ruta)).isEqualTo("null|null|null");
    }

    static String tokenDe(UUID cuenta) {
        try {
            var claims = new JWTClaimsSet.Builder().issuer("servired-identidad").audience("servired-api")
                    .subject(cuenta.toString()).claim("rol", "CIUDADANO")
                    .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(600))).build();
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(CLAVE.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(CLAVE));
            return "Bearer " + jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void crearReportesTieneLimitePorCuentaSinAfectarAOtrasCuentasNiALasConsultas() {
        String ana = tokenDe(UUID.randomUUID());
        for (int i = 0; i < 3; i++) {
            cliente.post().uri("/api/v1/reportes").header("Authorization", ana).exchange().expectStatus().isOk();
        }
        var r = cliente.post().uri("/api/v1/reportes").header("Authorization", ana).exchange()
                .returnResult(String.class);
        assertThat(r.getStatus().value()).isEqualTo(429);
        assertThat(r.getResponseHeaders().getFirst("Retry-After")).isNotBlank();
        assertThat(r.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        // Otra cuenta (aunque venga de la misma IP) sigue reportando, y Ana puede seguir consultando.
        cliente.post().uri("/api/v1/reportes").header("Authorization", tokenDe(UUID.randomUUID())).exchange()
                .expectStatus().isOk();
        get("/api/v1/reportes", ana).expectStatus().isOk();
    }

    @Test
    void validarTieneSuPropioCupoPorCuenta() {
        String beto = tokenDe(UUID.randomUUID());
        for (int i = 0; i < 3; i++) {
            cliente.post().uri("/api/v1/reportes").header("Authorization", beto).exchange().expectStatus().isOk();
        }
        // Agotó el cupo de reportes, pero validar es otra acción con su propio cupo.
        cliente.post().uri("/api/v1/reportes/" + UUID.randomUUID() + "/validaciones").header("Authorization", beto)
                .exchange().expectStatus().isOk();
    }

}
