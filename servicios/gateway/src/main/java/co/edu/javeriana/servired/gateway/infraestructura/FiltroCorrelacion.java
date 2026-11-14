package co.edu.javeriana.servired.gateway.infraestructura;

import co.edu.javeriana.servired.gateway.dominio.IdCorrelacion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Garantiza que toda petición que entra al gateway lleve un X-Correlation-Id
 * válido, lo propaga al microservicio destino y lo devuelve al cliente.
 *
 * <p>Es un {@link WebFilter} (no un GlobalFilter) para cubrir también las
 * peticiones que no se enrutan, como health o rutas inexistentes.
 *
 * <p>El log de acceso solo registra método, ruta, estado y duración: nunca IP,
 * cabeceras ni cuerpo (RNF-OBS2, RNF-SEG4).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FiltroCorrelacion implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(FiltroCorrelacion.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        IdCorrelacion id = IdCorrelacion.desdeCabecera(
                exchange.getRequest().getHeaders().getFirst(IdCorrelacion.CABECERA));

        ServerWebExchange conCorrelacion = exchange.mutate()
                .request(r -> r.headers(h -> h.set(IdCorrelacion.CABECERA, id.valor())))
                .build();
        conCorrelacion.getResponse().getHeaders().set(IdCorrelacion.CABECERA, id.valor());

        long inicio = System.nanoTime();
        return chain.filter(conCorrelacion)
                .doFinally(senal -> log.atInfo()
                        .addKeyValue("correlationId", id.valor())
                        .addKeyValue("metodo", exchange.getRequest().getMethod().name())
                        .addKeyValue("ruta", exchange.getRequest().getPath().value())
                        .addKeyValue("estado", estado(conCorrelacion))
                        .addKeyValue("duracionMs", (System.nanoTime() - inicio) / 1_000_000)
                        .log("peticion atendida"));
    }

    private static int estado(ServerWebExchange exchange) {
        var codigo = exchange.getResponse().getStatusCode();
        return codigo == null ? 0 : codigo.value();
    }
}
