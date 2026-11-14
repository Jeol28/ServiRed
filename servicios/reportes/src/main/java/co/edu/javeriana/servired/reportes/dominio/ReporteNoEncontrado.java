package co.edu.javeriana.servired.reportes.dominio;

import java.util.UUID;

/** No existe o no pertenece al ciudadano (no se revela cuál de las dos). */
public class ReporteNoEncontrado extends RuntimeException {

    public ReporteNoEncontrado(UUID idReporte) {
        super("Reporte no encontrado: " + idReporte);
    }
}
