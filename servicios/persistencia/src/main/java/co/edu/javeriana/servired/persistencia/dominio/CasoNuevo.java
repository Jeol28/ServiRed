package co.edu.javeriana.servired.persistencia.dominio;

import java.time.Instant;
import java.util.UUID;

/** Caso de atención que se abre a partir de un reporte. */
public record CasoNuevo(UUID idCaso, UUID idReporte, String tipoServicio,
                        String codigoMunicipio, String magnitud, Instant abiertoEn) {

    public static final String ESTADO_INICIAL = "RECIBIDO";
}
