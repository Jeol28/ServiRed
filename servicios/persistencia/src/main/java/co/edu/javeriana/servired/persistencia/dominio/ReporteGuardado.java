package co.edu.javeriana.servired.persistencia.dominio;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Reporte tal como quedó persistido, con historia, validaciones y evidencias. */
public record ReporteGuardado(UUID idReporte, UUID idCiudadano, String tipoServicio,
                              double latitud, double longitud, String codigoMunicipio,
                              String nombreMunicipio, Instant inicioEstimado, String magnitud,
                              String descripcion, String estado, Instant fechaRegistro,
                              Instant fechaRestablecimiento, Integer duracionMinutos,
                              int versionValidaciones, Conteo validacionCorte, Conteo validacionRestablecimiento,
                              List<EntradaHistoria> historia, List<EvidenciaGuardada> evidencias) {

    public record EntradaHistoria(String estado, Instant fecha, String detalle) {
    }

    public record Conteo(int confirmaciones, int controversias) {
        public static final Conteo VACIO = new Conteo(0, 0);
    }

    public record EvidenciaGuardada(UUID idEvidencia, String tipoContenido, int tamanoBytes, Instant creadaEn) {
    }
}
