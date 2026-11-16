package co.edu.javeriana.servired.persistencia.dominio;

import java.time.Instant;
import java.util.UUID;

/** Caso de atención que se abre a partir de un reporte, ya enrutado y priorizado por Atención. */
public record CasoNuevo(UUID idCaso, UUID idReporte, String tipoServicio, String codigoMunicipio, String magnitud,
                        Instant abiertoEn, UUID idEmpresa, String prioridad, Double latitud, Double longitud,
                        String descripcion, Instant inicioEstimado) {

    public static final String ESTADO_INICIAL = "RECIBIDO";

    public CasoNuevo {
        prioridad = prioridad == null ? "MEDIA" : prioridad;
    }
}
