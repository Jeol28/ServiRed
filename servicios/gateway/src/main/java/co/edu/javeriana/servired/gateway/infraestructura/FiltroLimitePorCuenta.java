package co.edu.javeriana.servired.gateway.infraestructura;

import co.edu.javeriana.servired.gateway.dominio.LimitadorVentanaFija;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/**
 * Límite de abuso por cuenta (RNF-SEG5) en las escrituras de los ciudadanos: crear
 * reportes, validar y subir fotos. Con el registro libre, sin este límite una cuenta podía
 * inundar el sistema. Corre después de Spring Security (orden -100), así que ya conoce la
 * cuenta (el subject del JWT): una solicitud sin token ni siquiera llega aquí.
 * <p>
 * Se cuenta por cuenta (el sub del JWT) y no por IP: muchos usuarios móviles comparten
 * IP detrás del NAT del operador (compensación T4). Dos ventanas por acción: por hora
 * (el límite) y por minuto (ráfagas). La cuenta solo vive en memoria como clave del
 * contador. Varias cuentas de una misma persona no se frenan aquí: contra el fraude en la
 * validación está la regla de antigüedad de las cuentas (ADR-22).
 */
@Component
@Order(0)
public class FiltroLimitePorCuenta implements WebFilter {

    record Regla(String nombre, HttpMethod metodo, PathPattern ruta, List<LimitadorVentanaFija> ventanas) {
    }

    private final List<Regla> reglas;

    public FiltroLimitePorCuenta(
            @Value("${servired.limites.por-cuenta.reportes-por-minuto:5}") int reportesPorMinuto,
            @Value("${servired.limites.por-cuenta.reportes-por-hora:10}") int reportesPorHora,
            @Value("${servired.limites.por-cuenta.validaciones-por-minuto:10}") int validacionesPorMinuto,
            @Value("${servired.limites.por-cuenta.validaciones-por-hora:30}") int validacionesPorHora,
            @Value("${servired.limites.por-cuenta.fotos-por-minuto:10}") int fotosPorMinuto,
            @Value("${servired.limites.por-cuenta.fotos-por-hora:30}") int fotosPorHora) {
        Clock reloj = Clock.systemUTC();
        this.reglas = List.of(
                regla("reportes", "/api/v1/reportes", reportesPorMinuto, reportesPorHora, reloj),
                regla("validaciones", "/api/v1/reportes/{id}/validaciones", validacionesPorMinuto,
                        validacionesPorHora, reloj),
                regla("fotos", "/api/v1/reportes/{id}/evidencias", fotosPorMinuto, fotosPorHora, reloj));
    }

    private static Regla regla(String nombre, String ruta, int porMinuto, int porHora, Clock reloj) {
        return new Regla(nombre, HttpMethod.POST, PathPatternParser.defaultInstance.parse(ruta), List.of(
                new LimitadorVentanaFija(porMinuto, Duration.ofMinutes(1), reloj),
                new LimitadorVentanaFija(porHora, Duration.ofHours(1), reloj)));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var peticion = exchange.getRequest();
        Optional<Regla> regla = reglas.stream()
                .filter(r -> r.metodo().equals(peticion.getMethod()) && r.ruta().matches(peticion.getPath().pathWithinApplication()))
                .findFirst();
        if (regla.isEmpty()) {
            return chain.filter(exchange);
        }
        return exchange.getPrincipal()
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(cuenta -> cuenta.isEmpty() ? chain.filter(exchange)
                        : aplicar(regla.get(), cuenta.get(), exchange, chain));
    }

    private Mono<Void> aplicar(Regla regla, Principal cuenta, ServerWebExchange exchange, WebFilterChain chain) {
        String clave = regla.nombre() + ":" + cuenta.getName();
        long esperar = 0;
        for (LimitadorVentanaFija ventana : regla.ventanas()) {
            var resultado = ventana.intentar(clave);
            if (!resultado.permitido()) {
                esperar = Math.max(esperar, resultado.segundosParaReintentar());
            }
        }
        if (esperar == 0) {
            return chain.filter(exchange);
        }
        exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(esperar));
        return Problemas.escribir(exchange, HttpStatus.TOO_MANY_REQUESTS, "Demasiadas solicitudes",
                "Su cuenta alcanzó el máximo de " + regla.nombre() + " por ahora. Espere un momento.");
    }
}
