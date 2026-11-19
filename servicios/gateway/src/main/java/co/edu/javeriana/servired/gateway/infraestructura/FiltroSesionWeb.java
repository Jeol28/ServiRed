package co.edu.javeriana.servired.gateway.infraestructura;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Map;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Sesión web con el token de refresco en una cookie HttpOnly (ADR-30).
 *
 * <p>Solo actúa si la petición trae {@code X-Sesion-Web: cookie} (el portal de operación). Las
 * apps móviles no la envían y siguen recibiendo el refresco en el JSON (lo guardan cifrado en
 * el dispositivo), así que Identidad y su contrato no cambian:
 * <ul>
 * <li><b>login y refrescar:</b> saca {@code tokenRefresco} del cuerpo de la respuesta y lo
 * devuelve en la cookie {@value #COOKIE}: {@code HttpOnly} (ningún script la lee, ni siquiera
 * tras una XSS), {@code SameSite=Strict}, {@code Path=/api/v1/auth} (no viaja a ninguna otra
 * ruta) y {@code Secure} salvo en localhost. Es de sesión del navegador: sin fecha de
 * expiración.</li>
 * <li><b>refrescar y cerrar-sesion:</b> toma el refresco de la cookie y se lo pasa a Identidad
 * en el cuerpo, como antes. Si la renovación falla o se cierra la sesión, expira la cookie.</li>
 * </ul>
 * CSRF: la cookie es SameSite=Strict y además solo se usa con la cabecera personalizada, que un
 * sitio ajeno no puede enviar sin una preflight CORS que el gateway no autoriza.
 */
@Component
public class FiltroSesionWeb implements GlobalFilter, Ordered {

    public static final String CABECERA = "X-Sesion-Web";
    public static final String COOKIE = "servired_refresco";
    static final String RUTA_COOKIE = "/api/v1/auth";
    static final String LOGIN = "/api/v1/auth/login";
    static final String REFRESCAR = "/api/v1/auth/refrescar";
    static final String CERRAR = "/api/v1/auth/cerrar-sesion";
    private static final String CAMPO = "tokenRefresco";

    private final ObjectMapper json = new ObjectMapper();

    @Override
    public int getOrder() {
        return -10;   // antes de NettyWriteResponseFilter (-1): puede reescribir la respuesta
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var peticion = exchange.getRequest();
        if (!"cookie".equalsIgnoreCase(peticion.getHeaders().getFirst(CABECERA)) || peticion.getMethod() != HttpMethod.POST) {
            return chain.filter(exchange);
        }
        String ruta = peticion.getPath().value();
        boolean segura = !esLocal(peticion);
        HttpCookie cookie = peticion.getCookies().getFirst(COOKIE);
        String refresco = cookie == null || cookie.getValue().isBlank() ? null : cookie.getValue();
        return switch (ruta) {
            case LOGIN -> chain.filter(conRespuesta(exchange, sinCabecera(peticion), segura, false));
            case REFRESCAR -> refresco == null
                    ? sinSesion(exchange, segura)
                    : chain.filter(conRespuesta(exchange, conCuerpo(peticion, refresco), segura, true));
            case CERRAR -> {
                exchange.getResponse().getHeaders().add(HttpHeaders.SET_COOKIE, expirada(segura).toString());
                yield refresco == null
                        ? Mono.fromRunnable(() -> exchange.getResponse().setStatusCode(HttpStatus.NO_CONTENT))
                        : chain.filter(exchange.mutate().request(conCuerpo(peticion, refresco)).build());
            }
            default -> chain.filter(exchange);
        };
    }

    private Mono<Void> sinSesion(ServerWebExchange exchange, boolean segura) {
        exchange.getResponse().getHeaders().add(HttpHeaders.SET_COOKIE, expirada(segura).toString());
        return Problemas.escribir(exchange, HttpStatus.UNAUTHORIZED, "Sesión inválida", "No hay una sesión que renovar");
    }

    /** El portal llega por su nginx con Host = el del navegador; en localhost no hay HTTPS. */
    private static boolean esLocal(ServerHttpRequest peticion) {
        String host = peticion.getHeaders().getHost() == null ? "" : peticion.getHeaders().getHost().getHostString();
        return host.equals("localhost") || host.equals("127.0.0.1");
    }

    private static ResponseCookie cookie(String valor, boolean segura) {
        return ResponseCookie.from(COOKIE, valor).httpOnly(true).secure(segura).sameSite("Strict").path(RUTA_COOKIE)
                .build();
    }

    private static ResponseCookie expirada(boolean segura) {
        return ResponseCookie.from(COOKIE, "").httpOnly(true).secure(segura).sameSite("Strict").path(RUTA_COOKIE)
                .maxAge(0).build();
    }

    private static ServerHttpRequest sinCabecera(ServerHttpRequest peticion) {
        return peticion.mutate().headers(h -> h.remove(CABECERA)).build();
    }

    /** La petición a Identidad, con el refresco de la cookie en el cuerpo y sin la cookie. */
    private ServerHttpRequest conCuerpo(ServerHttpRequest peticion, String refresco) {
        byte[] cuerpo;
        try {
            cuerpo = json.writeValueAsBytes(Map.of(CAMPO, refresco));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        var sinCookie = peticion.mutate().headers(h -> {
            h.remove(CABECERA);
            h.remove(HttpHeaders.COOKIE);
            h.remove(HttpHeaders.TRANSFER_ENCODING);
            h.setContentType(MediaType.APPLICATION_JSON);
            h.setContentLength(cuerpo.length);
        }).build();
        return new ServerHttpRequestDecorator(sinCookie) {
            @Override
            public Flux<DataBuffer> getBody() {
                return Flux.defer(() -> Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(cuerpo)));
            }
        };
    }

    /** Respuesta de Identidad: el refresco pasa del cuerpo a la cookie; si la renovación falló, se expira. */
    private ServerWebExchange conRespuesta(ServerWebExchange exchange, ServerHttpRequest peticion, boolean segura,
                                           boolean expirarSiFalla) {
        ServerHttpResponse original = exchange.getResponse();
        var respuesta = new ServerHttpResponseDecorator(original) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> cuerpo) {
                HttpStatus estado = HttpStatus.resolve(getStatusCode() == null ? 200 : getStatusCode().value());
                if (estado == null || !estado.is2xxSuccessful()) {
                    if (expirarSiFalla) {
                        getHeaders().add(HttpHeaders.SET_COOKIE, expirada(segura).toString());
                    }
                    return super.writeWith(cuerpo);
                }
                return DataBufferUtils.join(Flux.from(cuerpo)).flatMap(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    byte[] nuevo = moverACookie(bytes, getHeaders(), segura);
                    getHeaders().setContentLength(nuevo.length);
                    return super.writeWith(Mono.just(bufferFactory().wrap(nuevo)));
                });
            }
        };
        return exchange.mutate().request(peticion).response(respuesta).build();
    }

    private byte[] moverACookie(byte[] cuerpo, HttpHeaders cabeceras, boolean segura) {
        try {
            JsonNode nodo = json.readTree(cuerpo);
            if (nodo instanceof ObjectNode objeto && objeto.hasNonNull(CAMPO)) {
                String refresco = objeto.remove(CAMPO).asText();
                cabeceras.add(HttpHeaders.SET_COOKIE, cookie(refresco, segura).toString());
                return json.writeValueAsBytes(objeto);
            }
        } catch (IOException e) {
            // No es JSON: se entrega tal cual.
        }
        return cuerpo;
    }
}
