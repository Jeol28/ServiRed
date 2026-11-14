package co.edu.javeriana.servired.persistencia.aplicacion;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioReportes;
import co.edu.javeriana.servired.persistencia.dominio.OperacionNegocio;
import co.edu.javeriana.servired.persistencia.dominio.ReporteGuardado;
import co.edu.javeriana.servired.persistencia.dominio.ServicioCliente;
import java.util.Optional;
import java.util.UUID;

/** consultarReporte: un reporte con su historia. */
public class ConsultarReporte {

    private final RepositorioReportes reportes;

    public ConsultarReporte(RepositorioReportes reportes) {
        this.reportes = reportes;
    }

    public Optional<ReporteGuardado> ejecutar(ServicioCliente servicio, UUID idReporte) {
        OperacionNegocio.CONSULTAR_REPORTE.autorizar(servicio);
        return reportes.buscar(idReporte);
    }
}
