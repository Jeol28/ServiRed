package co.edu.javeriana.servired.reportes.aplicacion;

import co.edu.javeriana.servired.reportes.aplicacion.puertos.RepositorioReportes;
import co.edu.javeriana.servired.reportes.dominio.IdCorrelacion;
import co.edu.javeriana.servired.reportes.dominio.Reporte;
import co.edu.javeriana.servired.reportes.dominio.ReporteNoEncontrado;
import java.util.UUID;

/** RF-2: el ciudadano consulta el estado e historia de SUS reportes. */
public class ConsultarReporte {

    private final RepositorioReportes repositorio;

    public ConsultarReporte(RepositorioReportes repositorio) {
        this.repositorio = repositorio;
    }

    public Reporte ejecutar(UUID idCiudadano, UUID idReporte, IdCorrelacion correlacion) {
        return repositorio.buscar(idReporte, correlacion)
                .filter(r -> r.perteneceA(idCiudadano))
                .orElseThrow(() -> new ReporteNoEncontrado(idReporte));
    }
}
