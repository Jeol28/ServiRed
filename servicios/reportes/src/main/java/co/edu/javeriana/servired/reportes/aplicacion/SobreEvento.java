package co.edu.javeriana.servired.reportes.aplicacion;

import co.edu.javeriana.servired.reportes.dominio.IdCorrelacion;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Sobre común de eventos (contratos/eventos/esquemas/sobre-evento.v1.json). */
public record SobreEvento(UUID idEvento, String tipo, int version, Instant fechaOcurrencia,
                          UUID correlationId, String origen, Map<String, Object> datos) {

    public static final String ORIGEN = "reportes";

    public static SobreEvento nuevo(String tipo, int version, IdCorrelacion correlacion, Instant ahora,
                                    Map<String, Object> datos) {
        return new SobreEvento(UUID.randomUUID(), tipo, version, ahora, UUID.fromString(correlacion.valor()),
                ORIGEN, Map.copyOf(datos));
    }
}
