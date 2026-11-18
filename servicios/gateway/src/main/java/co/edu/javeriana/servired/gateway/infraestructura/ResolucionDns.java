package co.edu.javeriana.servired.gateway.infraestructura;

import java.time.Duration;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DNS de los servicios con caché corta (ADR-20). El cliente HTTP del gateway (Reactor
 * Netty) usa su propio resolvedor, que ignora la caché de la JVM y guarda cada respuesta
 * lo que diga el DNS de Docker (600 s). Al recrearse un contenedor con otra IP (o al
 * escalar), el gateway seguía enviando a la IP vieja, que podía ser ya de OTRO servicio:
 * un POST /api/v1/reportes terminó respondido por un servicio .NET con 404.
 */
@Configuration
public class ResolucionDns {

    /** Igual que la caché de DNS de la JVM en los servicios (-Dsun.net.inetaddr.ttl=5). */
    public static final Duration CACHE_MAXIMA = Duration.ofSeconds(5);

    @Bean
    HttpClientCustomizer cacheDnsCorta() {
        return cliente -> cliente.resolver(r -> r.cacheMaxTimeToLive(CACHE_MAXIMA).cacheNegativeTimeToLive(Duration.ofSeconds(1)));
    }
}
