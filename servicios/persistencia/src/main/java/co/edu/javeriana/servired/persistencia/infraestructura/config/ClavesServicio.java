package co.edu.javeriana.servired.persistencia.infraestructura.config;

import co.edu.javeriana.servired.persistencia.dominio.ServicioCliente;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Clave propia de cada servicio (PERSISTENCIA_CLAVE_<SERVICIO> en .env). Una
 * clave vacía deshabilita a ese servicio. La comparación es de tiempo constante.
 */
@ConfigurationProperties(prefix = "servired")
public record ClavesServicio(Map<ServicioCliente, String> claves) {

    private static final int LONGITUD_MINIMA = 16;

    public ClavesServicio {
        claves = claves == null ? new EnumMap<>(ServicioCliente.class) : new EnumMap<>(claves);
    }

    public Optional<ServicioCliente> identificar(String claveRecibida) {
        if (claveRecibida == null || claveRecibida.isBlank()) {
            return Optional.empty();
        }
        byte[] recibida = claveRecibida.getBytes(StandardCharsets.UTF_8);
        ServicioCliente encontrado = null;
        for (var entrada : claves.entrySet()) {
            String esperada = entrada.getValue();
            if (esperada != null && esperada.length() >= LONGITUD_MINIMA
                    && MessageDigest.isEqual(esperada.getBytes(StandardCharsets.UTF_8), recibida)) {
                encontrado = entrada.getKey();
            }
        }
        return Optional.ofNullable(encontrado);
    }
}
