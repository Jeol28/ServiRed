package co.edu.javeriana.servired.persistencia.infraestructura.web;

import co.edu.javeriana.servired.persistencia.dominio.EventoSaliente;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Traduce el sobre de evento recibido al modelo de dominio, conservando el JSON original. */
@Component
public class Solicitudes {

    private final ObjectMapper json;

    public Solicitudes(ObjectMapper json) {
        this.json = json;
    }

    EventoSaliente evento(EventoDto e) {
        try {
            return new EventoSaliente(e.idEvento(), e.tipo(), e.version(), e.correlationId(), e.origen(),
                    json.writeValueAsString(e));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Evento no serializable", ex);
        }
    }

    /** Sobre común (contratos/eventos/esquemas/sobre-evento.v1.json). */
    public record EventoDto(
            @NotNull UUID idEvento,
            @NotBlank String tipo,
            @Min(1) int version,
            @NotNull Instant fechaOcurrencia,
            @NotNull UUID correlationId,
            @NotBlank String origen,
            @NotNull JsonNode datos) {
    }
}
