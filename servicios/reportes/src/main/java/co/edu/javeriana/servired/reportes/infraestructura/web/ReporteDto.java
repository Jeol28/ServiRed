package co.edu.javeriana.servired.reportes.infraestructura.web;

import co.edu.javeriana.servired.reportes.dominio.EstadoReporte;
import co.edu.javeriana.servired.reportes.dominio.Magnitud;
import co.edu.javeriana.servired.reportes.dominio.Reporte;
import co.edu.javeriana.servired.reportes.dominio.TipoServicio;
import co.edu.javeriana.servired.reportes.infraestructura.web.ControladorReportes.UbicacionDto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Esquema Reporte de la API pública. No expone el identificador del ciudadano. */
public record ReporteDto(UUID idReporte, TipoServicio tipoServicio, UbicacionDto ubicacion, String codigoMunicipio,
                         String nombreMunicipio, Instant inicioEstimado, Magnitud magnitud, String descripcion,
                         EstadoReporte estado, Instant fechaRegistro, List<EntradaDto> historia) {

    static ReporteDto de(Reporte r) {
        return new ReporteDto(r.idReporte(), r.tipoServicio(),
                new UbicacionDto(r.ubicacion().latitud(), r.ubicacion().longitud()), r.municipio().codigo(),
                r.municipio().nombre(), r.inicioEstimado(), r.magnitud(), r.descripcion(), r.estado(),
                r.fechaRegistro(),
                r.historia().stream().map(h -> new EntradaDto(h.estado(), h.fecha(), h.detalle())).toList());
    }

    public record EntradaDto(EstadoReporte estado, Instant fecha, String detalle) {
    }
}
