package co.edu.javeriana.servired.reportes.infraestructura.persistencia;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Conexión con el servicio de Persistencia. La clave es la de ESTE servicio
 * (PERSISTENCIA_CLAVE_REPORTES en .env); nunca hay credenciales de base de datos.
 */
@ConfigurationProperties(prefix = "servired.persistencia")
public record PropiedadesPersistencia(String url, String clave, Duration timeoutConexion,
                                      Duration timeoutLectura, int intentos, Duration esperaEntreIntentos) {

    public PropiedadesPersistencia {
        timeoutConexion = timeoutConexion == null ? Duration.ofSeconds(2) : timeoutConexion;
        timeoutLectura = timeoutLectura == null ? Duration.ofSeconds(5) : timeoutLectura;
        intentos = intentos <= 0 ? 3 : intentos;
        esperaEntreIntentos = esperaEntreIntentos == null ? Duration.ofMillis(200) : esperaEntreIntentos;
    }
}
