package co.edu.javeriana.servired.gateway.infraestructura;

import co.edu.javeriana.servired.gateway.dominio.LimitadorVentanaFija;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Límite de abuso (RNF-SEG5) en las rutas públicas sensibles: autenticación
 * (fuerza bruta) y denuncias anónimas (spam). La IP nunca se guarda ni se
 * registra: solo su SHA-256 con una sal aleatoria por proceso, en memoria.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class FiltroLimiteSolicitudes implements WebFilter {

    private final LimitadorVentanaFija autenticacion;
    private final LimitadorVentanaFija denuncias;
    private final byte[] sal = new byte[32];

    public FiltroLimiteSolicitudes(
            @Value("${servired.limites.autenticacion-por-minuto:20}") int autenticacionPorMinuto,
            @Value("${servired.limites.denuncias-por-minuto:5}") int denunciasPorMinuto) {
        Clock reloj = Clock.systemUTC();
        this.autenticacion = new LimitadorVentanaFija(autenticacionPorMinuto, Duration.ofMinutes(1), reloj);
        this.denuncias = new LimitadorVentanaFija(denunciasPorMinuto, Duration.ofMinutes(1), reloj);
        new SecureRandom().nextBytes(sal);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String ruta = exchange.getRequest().getPath().value();
        LimitadorVentanaFija limitador = ruta.startsWith("/api/v1/auth/") ? autenticacion
                : ruta.startsWith("/api/v1/denuncias") ? denuncias : null;
        if (limitador == null) {
            return chain.filter(exchange);
        }
        var resultado = limitador.intentar(huellaCliente(exchange));
        if (resultado.permitido()) {
            return chain.filter(exchange);
        }
        exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(resultado.segundosParaReintentar()));
        return Problemas.escribir(exchange, HttpStatus.TOO_MANY_REQUESTS, "Demasiadas solicitudes",
                "Espere un momento antes de intentarlo de nuevo");
    }

    private String huellaCliente(ServerWebExchange exchange) {
        InetSocketAddress remota = exchange.getRequest().getRemoteAddress();
        String ip = remota == null || remota.getAddress() == null ? "desconocida" : remota.getAddress().getHostAddress();
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(sal);
            return HexFormat.of().formatHex(sha.digest(ip.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
