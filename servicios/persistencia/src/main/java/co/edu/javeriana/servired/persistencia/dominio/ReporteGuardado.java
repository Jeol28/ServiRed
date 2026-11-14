package co.edu.javeriana.servired.persistencia.dominio;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Reporte tal como quedó persistido, con su historia de estados. */
public record ReporteGuardado(UUID idReporte, UUID idCiudadano, String tipoServicio,
                              double latitud, double longitud, String codigoMunicipio,
                              String nombreMunicipio, Instant inicioEstimado, String magnitud,
                              String descripcion, String estado, Instant fechaRegistro,
                              List<EntradaHistoria> historia) {

    public record EntradaHistoria(String estado, Instant fecha, String detalle) {
    }
}
