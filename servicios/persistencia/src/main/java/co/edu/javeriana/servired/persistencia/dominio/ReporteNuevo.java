package co.edu.javeriana.servired.persistencia.dominio;

import java.time.Instant;
import java.util.UUID;

/** Reporte validado por el servicio Reportes, listo para guardar. */
public record ReporteNuevo(UUID idReporte, UUID idCiudadano, String tipoServicio,
                           double latitud, double longitud, String codigoMunicipio,
                           Instant inicioEstimado, String magnitud, String descripcion,
                           Instant fechaRegistro) {

    public static final String ESTADO_INICIAL = "RECIBIDO";
}
