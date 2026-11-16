package co.edu.javeriana.servired.identidad.infraestructura.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Emisión de tokens. {@code clavePrivada} es la clave RSA en PKCS#8 DER y Base64
 * en una sola línea (IDENTIDAD_JWT_CLAVE_PRIVADA en .env). Si está vacía se
 * genera una clave efímera: los tokens dejan de valer al reiniciar.
 */
@ConfigurationProperties(prefix = "servired.jwt")
public record PropiedadesJwt(String emisor, String audiencia, Duration duracionAcceso, Duration duracionRefresco,
                             String clavePrivada) {

    public PropiedadesJwt {
        emisor = emisor == null ? "servired-identidad" : emisor;
        audiencia = audiencia == null ? "servired-api" : audiencia;
        duracionAcceso = duracionAcceso == null ? Duration.ofMinutes(15) : duracionAcceso;
        duracionRefresco = duracionRefresco == null ? Duration.ofDays(14) : duracionRefresco;
    }
}
