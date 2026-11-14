package co.edu.javeriana.servired.persistencia.aplicacion.puertos;

import co.edu.javeriana.servired.persistencia.dominio.ReporteGuardado;
import co.edu.javeriana.servired.persistencia.dominio.ReporteNuevo;
import java.util.Optional;
import java.util.UUID;

public interface RepositorioReportes {

    void insertar(ReporteNuevo reporte);

    Optional<ReporteGuardado> buscar(UUID idReporte);
}
