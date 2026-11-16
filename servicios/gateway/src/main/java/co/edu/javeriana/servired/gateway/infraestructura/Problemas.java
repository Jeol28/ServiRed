package co.edu.javeriana.servired.gateway.infraestructura;

import co.edu.javeriana.servired.gateway.dominio.IdCorrelacion;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Respuestas de error del gateway en application/problem+json (RFC 9457). */
final class Problemas {

    private Problemas() {
    }

    static Mono<Void> escribir(ServerWebExchange exchange, HttpStatus estado, String titulo, String detalle) {
        var respuesta = exchange.getResponse();
        if (respuesta.isCommitted()) {
            return Mono.empty();
        }
        respuesta.setStatusCode(estado);
        respuesta.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        String correlacion = exchange.getRequest().getHeaders().getFirst(IdCorrelacion.CABECERA);
        byte[] cuerpo = "{\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\",\"correlationId\":\"%s\"}"
                .formatted(titulo, estado.value(), detalle, correlacion).getBytes(StandardCharsets.UTF_8);
        return respuesta.writeWith(Mono.just(respuesta.bufferFactory().wrap(cuerpo)));
    }
}
