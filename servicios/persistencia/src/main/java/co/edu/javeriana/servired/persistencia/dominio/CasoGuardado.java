package co.edu.javeriana.servired.persistencia.dominio;

import java.time.Instant;
import java.util.UUID;

/** Caso de atención persistido. */
public record CasoGuardado(UUID idCaso, UUID idReporte, String tipoServicio,
                           String codigoMunicipio, String magnitud, String estado,
                           Instant abiertoEn) {
}
