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
                         EstadoReporte estado, Instant fechaRegistro, Instant fechaRestablecimiento,
                         Integer duracionMinutos, ValidacionesDto validacion, List<EvidenciaDto> evidencias,
                         List<EntradaDto> historia) {

    static ReporteDto de(Reporte r) {
        return new ReporteDto(r.idReporte(), r.tipoServicio(),
                new UbicacionDto(r.ubicacion().latitud(), r.ubicacion().longitud()), r.municipio().codigo(),
                r.municipio().nombre(), r.inicioEstimado(), r.magnitud(), r.descripcion(), r.estado(),
                r.fechaRegistro(), r.fechaRestablecimiento(), r.duracionMinutos(),
                new ValidacionesDto(ConteoDto.de(r.validacionCorte()), ConteoDto.de(r.validacionRestablecimiento())),
                r.evidencias().stream().map(e -> new EvidenciaDto(e.idEvidencia(), e.tipoContenido(), e.tamanoBytes(),
                        e.creadaEn(), "/api/v1/reportes/" + r.idReporte() + "/evidencias/" + e.idEvidencia())).toList(),
                r.historia().stream().map(h -> new EntradaDto(h.estado(), h.fecha(), h.detalle())).toList());
    }

    public record EntradaDto(EstadoReporte estado, Instant fecha, String detalle) {
    }

    public record EvidenciaDto(UUID idEvidencia, String tipoContenido, int tamanoBytes, Instant creadaEn, String url) {
    }

    public record ValidacionesDto(ConteoDto corte, ConteoDto restablecimiento) {
    }

    /** El umbral lo aplica el dominio; aquí solo se expone el conteo y las netas. */
    public record ConteoDto(int confirmaciones, int controversias, int confirmacionesNetas) {
        static ConteoDto de(Reporte.Conteo c) {
            return new ConteoDto(c.confirmaciones(), c.controversias(), c.netas());
        }
    }
}
