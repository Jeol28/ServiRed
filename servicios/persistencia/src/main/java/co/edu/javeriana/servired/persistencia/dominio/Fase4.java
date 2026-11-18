package co.edu.javeriana.servired.persistencia.dominio;

import java.time.Instant;
import java.util.UUID;

/** Modelos de las operaciones de restablecimiento, validación, evidencias y denuncias. */
public final class Fase4 {

    private Fase4() {
    }

    public record Restablecimiento(Instant fechaRestablecimiento, int duracionMinutos, String comentario) {
    }

    /** @param versionEsperada versión de validaciones que leyó Reportes al calcular el resultado */
    /** cuenta = false: el voto se guarda pero no suma (cuenta demasiado nueva, ADR-22). */
    public record VotoNuevo(String objeto, String sentido, UUID idCiudadano, Instant fecha, int versionEsperada,
                            boolean cuenta) {
    }

    public record ArchivoNuevo(UUID idEvidencia, String tipoContenido, byte[] contenido) {

        public static final int TAMANO_MAXIMO = 6 * 1024 * 1024;

        public ArchivoNuevo {
            if (!"image/jpeg".equals(tipoContenido) && !"image/png".equals(tipoContenido)) {
                throw new SolicitudRechazada("Tipo de evidencia no permitido");
            }
            if (contenido == null || contenido.length == 0 || contenido.length > TAMANO_MAXIMO) {
                throw new SolicitudRechazada("Tamaño de evidencia no permitido");
            }
        }
    }

    public record Archivo(String tipoContenido, byte[] contenido) {
    }

    public record DenunciaNueva(UUID idDenuncia, String tipoServicio, double latitud, double longitud,
                                String codigoMunicipio, String descripcion, Instant fechaRecepcion) {
    }

    public record DenunciaGuardada(UUID idDenuncia, String tipoServicio, String codigoMunicipio, String estado,
                                   Instant fechaRecepcion, boolean tieneEvidencia) {
    }

    /** Vista mínima de un reporte cercano para validación comunitaria (sin datos del autor). */
    public record ReporteCercano(UUID idReporte, String tipoServicio, String magnitud, String estado,
                                 Instant inicioEstimado, double latitud, double longitud, int distanciaMetros,
                                 ReporteGuardado.Conteo validacionCorte,
                                 ReporteGuardado.Conteo validacionRestablecimiento) {
    }

    public record PaginaReportes(java.util.List<ReporteGuardado> elementos, long total, int pagina, int tamano) {
    }
}
