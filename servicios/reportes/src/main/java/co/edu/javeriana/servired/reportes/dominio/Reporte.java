package co.edu.javeriana.servired.reportes.dominio;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Reporte persistido con su historia de estados (RF-2). */
public record Reporte(UUID idReporte, UUID idCiudadano, TipoServicio tipoServicio, Ubicacion ubicacion,
                      Municipio municipio, Instant inicioEstimado, Magnitud magnitud, String descripcion,
                      EstadoReporte estado, Instant fechaRegistro, List<EntradaHistoria> historia) {

    public Reporte {
        historia = List.copyOf(historia);
    }

    public boolean perteneceA(UUID ciudadano) {
        return idCiudadano.equals(ciudadano);
    }

    public record EntradaHistoria(EstadoReporte estado, Instant fecha, String detalle) {
    }
}
