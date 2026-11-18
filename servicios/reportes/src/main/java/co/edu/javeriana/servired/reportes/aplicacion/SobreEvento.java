package co.edu.javeriana.servired.reportes.aplicacion;

import co.edu.javeriana.servired.reportes.dominio.IdCorrelacion;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Sobre común de eventos (contratos/eventos/esquemas/sobre-evento.v1.json).
 * fechaOcurrencia sale siempre como texto ISO-8601 (date-time del contrato), sin depender
 * de la configuración de Jackson: con WRITE_DATES_AS_TIMESTAMPS saldría como número.
 */
public record SobreEvento(UUID idEvento, String tipo, int version,
                          @JsonFormat(shape = JsonFormat.Shape.STRING) Instant fechaOcurrencia,
                          UUID correlationId, String origen, Map<String, Object> datos) {

    public static final String ORIGEN = "reportes";

    public static SobreEvento nuevo(String tipo, int version, IdCorrelacion correlacion, Instant ahora,
                                    Map<String, Object> datos) {
        return new SobreEvento(UUID.randomUUID(), tipo, version, ahora, UUID.fromString(correlacion.valor()),
                ORIGEN, Map.copyOf(datos));
    }
}
