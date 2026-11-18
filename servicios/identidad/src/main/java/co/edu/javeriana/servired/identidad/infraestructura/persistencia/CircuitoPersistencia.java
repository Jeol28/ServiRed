package co.edu.javeriana.servired.identidad.infraestructura.persistencia;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Crea el circuit breaker hacia Persistencia (ADR-20). Cuentan como falla la red
 * caída, los timeouts y los 5xx; un 4xx es un error de la petición, no de
 * Persistencia, y no cuenta.
 */
public final class CircuitoPersistencia {

    public static final String NOMBRE = "persistencia";
    private static final Logger log = LoggerFactory.getLogger(CircuitoPersistencia.class);

    private CircuitoPersistencia() {
    }

    public static CircuitBreaker crear(CircuitBreakerRegistry registro, PropiedadesCircuito p) {
        var config = CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(p.ventana())
                .minimumNumberOfCalls(p.minimoLlamadas())
                .failureRateThreshold(p.porcentajeFallas())
                .waitDurationInOpenState(p.esperaAbierto())
                .permittedNumberOfCallsInHalfOpenState(p.llamadasDePrueba())
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .recordExceptions(ResourceAccessException.class, HttpServerErrorException.class)
                .ignoreExceptions(HttpClientErrorException.class)
                .build();
        var circuito = registro.circuitBreaker(NOMBRE, config);
        circuito.getEventPublisher().onStateTransition(e -> {
            var t = e.getStateTransition();
            if (t.getToState() == CircuitBreaker.State.OPEN) {
                log.error("circuito hacia persistencia ABIERTO ({} -> {}): se falla rápido por {}",
                        t.getFromState(), t.getToState(), p.esperaAbierto());
            } else {
                log.warn("circuito hacia persistencia {} -> {}", t.getFromState(), t.getToState());
            }
        });
        return circuito;
    }

    public static CircuitBreaker crear(PropiedadesCircuito p) {
        return crear(CircuitBreakerRegistry.ofDefaults(), p);
    }
}
