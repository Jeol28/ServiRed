package co.edu.javeriana.servired.reportes.dominio;

import java.util.Optional;
import java.util.UUID;

/**
 * Identificador de correlación de una transacción (RNF-OBS1). Llega desde el
 * gateway; si falta o no es un UUID válido se genera uno nuevo.
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
                .filter(v -> v.length() == 36)
                .flatMap(IdCorrelacion::intentar)
                .orElseGet(IdCorrelacion::nuevo);
    }

    private static Optional<IdCorrelacion> intentar(String valor) {
        try {
            return Optional.of(new IdCorrelacion(valor.toLowerCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
