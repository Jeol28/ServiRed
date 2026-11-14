package co.edu.javeriana.servired.gateway.dominio;

import java.util.Optional;
import java.util.UUID;

/**
 * Identificador de correlación de una transacción (RNF-OBS1).
 *
 * <p>Se acepta el valor enviado por el cliente solo si es un UUID válido; en
 * cualquier otro caso se genera uno nuevo. Así se evita que un cliente inyecte
 * texto arbitrario en logs o lo use para correlacionar a un denunciante anónimo.
 */
public record IdCorrelacion(String valor) {

    public static final String CABECERA = "X-Correlation-Id";

    public IdCorrelacion {
        UUID.fromString(valor);
    }

    public static IdCorrelacion nuevo() {
        return new IdCorrelacion(UUID.randomUUID().toString());
    }

    public static IdCorrelacion desdeCabecera(String valorRecibido) {
        return Optional.ofNullable(valorRecibido)
                .filter(IdCorrelacion::esUuidValido)
                .map(v -> new IdCorrelacion(v.toLowerCase()))
                .orElseGet(IdCorrelacion::nuevo);
    }

    private static boolean esUuidValido(String valor) {
        if (valor.length() != 36) {
            return false;
        }
        try {
            UUID.fromString(valor);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
