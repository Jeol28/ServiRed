package co.edu.javeriana.servired.reportes.aplicacion;

import co.edu.javeriana.servired.reportes.dominio.EstadoReporte;
import co.edu.javeriana.servired.reportes.dominio.Magnitud;
import co.edu.javeriana.servired.reportes.dominio.ObjetoValidacion;
import co.edu.javeriana.servired.reportes.dominio.Reporte;
import co.edu.javeriana.servired.reportes.dominio.SentidoValidacion;
import co.edu.javeriana.servired.reportes.dominio.TipoServicio;
import co.edu.javeriana.servired.reportes.dominio.Ubicacion;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Modelos de entrada y salida de los casos de uso de reportes. */
public final class Vistas {

    private Vistas() {
    }

    public record PaginaReportes(List<Reporte> elementos, long total, int pagina, int tamano) {
    }

    /** Lo que ve otro ciudadano para validar: sin autor ni descripción. */
    public record ReporteCercano(UUID idReporte, TipoServicio tipoServicio, Magnitud magnitud, EstadoReporte estado,
                                 Instant inicioEstimado, Ubicacion ubicacion, int distanciaMetros,
                                 Reporte.Conteo validacionCorte, Reporte.Conteo validacionRestablecimiento) {
    }

    /** @param cuenta si suma al conteo (antigüedad de la cuenta, ADR-22) */
    public record Voto(ObjetoValidacion objeto, SentidoValidacion sentido, UUID idCiudadano, Instant fecha,
                       int versionEsperada, boolean cuenta) {
    }

    /** @param nuevo false si el ciudadano ya había votado (respuesta idempotente) */
    /** @param cuentaParaUmbral false si la cuenta es demasiado nueva: el voto quedó, pero no suma */
    public record ResultadoValidacion(UUID idReporte, ObjetoValidacion objeto, Reporte.Conteo conteo, boolean validado,
                                      boolean nuevo, boolean cuentaParaUmbral) {
    }

    public record ImagenSaneada(String tipoContenido, byte[] contenido, int ancho, int alto) {
    }

    public record Archivo(String tipoContenido, byte[] contenido) {
    }

    public record DenunciaRecibida(UUID idDenuncia, String estado, Instant fechaRecepcion, boolean tieneEvidencia) {
    }
}
