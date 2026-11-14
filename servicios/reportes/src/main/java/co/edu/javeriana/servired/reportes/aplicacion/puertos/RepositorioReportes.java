package co.edu.javeriana.servired.reportes.aplicacion.puertos;

import co.edu.javeriana.servired.reportes.aplicacion.SobreEvento;
import co.edu.javeriana.servired.reportes.dominio.IdCorrelacion;
import co.edu.javeriana.servired.reportes.dominio.NuevoReporte;
import co.edu.javeriana.servired.reportes.dominio.Reporte;
import java.util.Optional;
import java.util.UUID;

/**
 * Operaciones de negocio de Persistencia para el dominio de reportes. El
 * evento se entrega junto con el reporte y Persistencia lo publica solo si la
 * escritura se confirma (ADR-06). Es idempotente por X-Correlation-Id.
 */
public interface RepositorioReportes {

    Reporte guardar(NuevoReporte reporte, String codigoMunicipio, SobreEvento evento, IdCorrelacion correlacion);

    Optional<Reporte> buscar(UUID idReporte, IdCorrelacion correlacion);
}
