package co.edu.javeriana.servired.reportes.aplicacion;

import co.edu.javeriana.servired.reportes.aplicacion.puertos.RepositorioReportes;
import co.edu.javeriana.servired.reportes.dominio.EstadoPorCaso;
import co.edu.javeriana.servired.reportes.dominio.IdCorrelacion;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Mantiene el estado que ve el ciudadano a partir de los eventos de caso de
 * Atención (ADR-07). Reportes y Atención nunca se llaman: solo eventos.
 */
public class SeguimientoDeCasos {

    private final RepositorioReportes repositorio;

    public SeguimientoDeCasos(RepositorioReportes repositorio) {
        this.repositorio = repositorio;
    }

    /** @return el cambio aplicado, o vacío si el evento no afecta el estado del reporte */
    public Optional<EstadoPorCaso> aplicar(String tipoEvento, UUID idEvento, UUID idReporte, String estadoCaso,
                                           String resolucion, Instant fecha, IdCorrelacion correlacion) {
        var cambio = EstadoPorCaso.para(tipoEvento, estadoCaso, resolucion);
        cambio.ifPresent(c -> repositorio.aplicarEstadoDeCaso(idReporte, idEvento, c, fecha, correlacion));
        return cambio;
    }
}
