package co.edu.javeriana.servired.persistencia.dominio;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Caso de atención persistido con su historia y las evidencias del reporte. */
public record CasoGuardado(UUID idCaso, UUID idReporte, String tipoServicio, String codigoMunicipio,
                           String nombreMunicipio, String magnitud, String estado, Instant abiertoEn, UUID idEmpresa,
                           String prioridad, boolean prioridadManual, boolean corteValidado, Double latitud,
                           Double longitud, String descripcion, Instant inicioEstimado, UUID asignadoA,
                           String resolucion, Instant resueltoEn, Instant cerradoEn, int version,
                           List<EntradaHistoria> historia, List<ReporteGuardado.EvidenciaGuardada> evidencias) {

    public record EntradaHistoria(String estado, Instant fecha, String detalle, UUID autor) {
    }
}
