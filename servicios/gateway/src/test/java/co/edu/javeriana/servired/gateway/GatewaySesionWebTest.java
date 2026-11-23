package co.edu.javeriana.servired.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Sesión web con el refresco en una cookie HttpOnly (ADR-30): el gateway lo mueve entre la
 * cookie y el cuerpo que espera Identidad. Identidad es un servidor HTTP simulado que anota
 * lo que recibe.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "servired.limites.autenticacion-por-minuto=1000"})
@AutoConfigureWebTestClient
class GatewaySesionWebTest {

    static final Map<String, String> RECIBIDO = new ConcurrentHashMap<>();
    static final HttpServer IDENTIDAD = iniciar();

    @Autowired
    WebTestClient cliente;

    static HttpServer iniciar() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            s.createContext("/", x -> {
                String ruta = x.getRequestURI().getPath();
                String cuerpo = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                RECIBIDO.put(ruta, cuerpo);
                RECIBIDO.put(ruta + "#cookie", String.valueOf(x.getRequestHeaders().getFirst("Cookie")));
                RECIBIDO.put(ruta + "#sesionweb", String.valueOf(x.getRequestHeaders().getFirst("X-Sesion-Web")));
                int estado = 200;
                String respuesta;
                switch (ruta) {
                    case "/api/v1/auth/login" -> respuesta = sesion("acc-1", "ref-1");
                    case "/api/v1/auth/refrescar" -> {
                        if (cuerpo.contains("\"ref-1\"")) {
                            respuesta = sesion("acc-2", "ref-2");
                        } else {
                            estado = 401;
                            respuesta = "{\"title\":\"Sesión inválida\",\"status\":401}";
                        }
                    }
                    case "/api/v1/auth/cerrar-sesion" -> {
                        estado = 204;
                        respuesta = "";
                    }
                    case "/interno/v1/tokens-revocados" ->
                            respuesta = "{\"generadaEn\":\"" + Instant.now() + "\",\"revocados\":[]}";
                    default -> respuesta = "{}";
                }
                byte[] b = respuesta.getBytes(StandardCharsets.UTF_8);
                x.getResponseHeaders().add("Content-Type", estado == 401 ? "application/problem+json" : "application/json");
                x.sendResponseHeaders(estado, estado == 204 ? -1 : b.length);
                if (estado != 204) {
                    x.getResponseBody().write(b);
                }
                x.close();
            });
            s.start();
            return s;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String sesion(String acceso, String refresco) {
        return """
                {"tokenAcceso":"%s","tipoToken":"Bearer","expiraEn":900,"tokenRefresco":"%s",
                 "cuenta":{"idCuenta":"0b1d8f4e-6f3a-4c55-9d2e-1f0a7c9e2b11","rol":"OPERADOR"}}""".formatted(acceso, refresco);
    }

    @AfterAll
    static void detener() {
        IDENTIDAD.stop(0);
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry r) {
        String base = "http://localhost:" + IDENTIDAD.getAddress().getPort();
        r.add("servired.jwt.jwks-url", () -> base + "/.well-known/jwks.json");
        r.add("servired.jwt.revocacion.url", () -> base + "/interno/v1/tokens-revocados");
        for (String s : new String[]{"IDENTIDAD", "REPORTES", "ATENCION", "INDICADORES", "AUDITORIA"}) {
            r.add("SERVICIO_" + s + "_URL", () -> base);
        }
    }

    @BeforeEach
    void limpiar() {
        RECIBIDO.clear();
    }

    private WebTestClient.ResponseSpec post(String ruta, String cuerpo, boolean web, String cookie, String host) {
        var p = cliente.post().uri(ruta).contentType(MediaType.APPLICATION_JSON);
        if (web) {
            p = p.header("X-Sesion-Web", "cookie");
        }
        if (cookie != null) {
            p = p.cookie("servired_refresco", cookie);
        }
        if (host != null) {
            p = p.header(HttpHeaders.HOST, host);
        }
        return p.bodyValue(cuerpo).exchange();
    }

    private static String setCookie(WebTestClient.ResponseSpec r) {
        List<String> valores = r.returnResult(String.class).getResponseHeaders().get(HttpHeaders.SET_COOKIE);
        return valores == null ? "" : String.join("\n", valores);
    }

    @Test
    void elPortalRecibeElRefrescoSoloEnUnaCookieHttpOnly() {
        var r = post("/api/v1/auth/login", "{\"correo\":\"a@b.co\",\"clave\":\"x\"}", true, null, "servired.ejemplo.co");

        r.expectStatus().isOk().expectBody()
                .jsonPath("$.tokenAcceso").isEqualTo("acc-1")
                .jsonPath("$.tokenRefresco").doesNotExist()
                .jsonPath("$.cuenta.rol").isEqualTo("OPERADOR");
        String cookie = setCookie(post("/api/v1/auth/login", "{}", true, null, "servired.ejemplo.co"));
        assertThat(cookie).contains("servired_refresco=ref-1", "HttpOnly", "SameSite=Strict", "Path=/api/v1/auth",
                "Secure").doesNotContain("Max-Age");
        assertThat(RECIBIDO.get("/api/v1/auth/login#sesionweb")).as("la cabecera no llega a Identidad").isEqualTo("null");
    }

    @Test
    void enLocalhostLaCookieNoEsSecureParaQueFuncioneSinHttps() {
        String cookie = setCookie(post("/api/v1/auth/login", "{}", true, null, "localhost:4300"));
        assertThat(cookie).contains("servired_refresco=ref-1", "HttpOnly").doesNotContain("Secure");
    }

    @Test
    void lasAppsSiguenRecibiendoElRefrescoEnElCuerpo() {
        var r = post("/api/v1/auth/login", "{}", false, null, null);
        r.expectStatus().isOk().expectBody().jsonPath("$.tokenRefresco").isEqualTo("ref-1");
        assertThat(setCookie(post("/api/v1/auth/login", "{}", false, null, null))).isEmpty();

        post("/api/v1/auth/refrescar", "{\"tokenRefresco\":\"ref-1\"}", false, null, null)
                .expectStatus().isOk().expectBody().jsonPath("$.tokenRefresco").isEqualTo("ref-2");
    }

    @Test
    void renovarTomaElRefrescoDeLaCookieYLaRota() {
        var r = post("/api/v1/auth/refrescar", "{}", true, "ref-1", "servired.ejemplo.co");

        r.expectStatus().isOk().expectBody().jsonPath("$.tokenAcceso").isEqualTo("acc-2")
                .jsonPath("$.tokenRefresco").doesNotExist();
        assertThat(RECIBIDO.get("/api/v1/auth/refrescar")).isEqualTo("{\"tokenRefresco\":\"ref-1\"}");
        assertThat(RECIBIDO.get("/api/v1/auth/refrescar#cookie")).as("la cookie no llega a Identidad").isEqualTo("null");
        assertThat(setCookie(post("/api/v1/auth/refrescar", "{}", true, "ref-1", "servired.ejemplo.co")))
                .contains("servired_refresco=ref-2", "HttpOnly");
    }

    @Test
    void sinCookieOConUnaInvalidaLaSesionTerminaYLaCookieSeBorra() {
        var sin = post("/api/v1/auth/refrescar", "{}", true, null, "servired.ejemplo.co");
        sin.expectStatus().isUnauthorized();
        assertThat(setCookie(post("/api/v1/auth/refrescar", "{}", true, null, "servired.ejemplo.co")))
                .contains("servired_refresco=", "Max-Age=0");
        assertThat(RECIBIDO).as("sin cookie ni siquiera llega a Identidad").doesNotContainKey("/api/v1/auth/refrescar");

        post("/api/v1/auth/refrescar", "{}", true, "robado", "servired.ejemplo.co").expectStatus().isUnauthorized();
        assertThat(setCookie(post("/api/v1/auth/refrescar", "{}", true, "robado", "servired.ejemplo.co")))
                .contains("Max-Age=0");
    }

    @Test
    void cerrarSesionRevocaElRefrescoDeLaCookieYLaBorra() {
        var r = post("/api/v1/auth/cerrar-sesion", "{}", true, "ref-2", "servired.ejemplo.co");

        r.expectStatus().isNoContent();
        assertThat(RECIBIDO.get("/api/v1/auth/cerrar-sesion")).isEqualTo("{\"tokenRefresco\":\"ref-2\"}");
        assertThat(setCookie(post("/api/v1/auth/cerrar-sesion", "{}", true, "ref-2", "servired.ejemplo.co")))
                .contains("servired_refresco=", "Max-Age=0");
        post("/api/v1/auth/cerrar-sesion", "{}", true, null, "servired.ejemplo.co").expectStatus().isNoContent();
    }
}
