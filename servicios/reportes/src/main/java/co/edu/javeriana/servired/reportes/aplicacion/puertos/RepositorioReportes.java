package co.edu.javeriana.servired.reportes.aplicacion.puertos;

import co.edu.javeriana.servired.reportes.aplicacion.SobreEvento;
import co.edu.javeriana.servired.reportes.aplicacion.Vistas.Archivo;
import co.edu.javeriana.servired.reportes.aplicacion.Vistas.DenunciaRecibida;
import co.edu.javeriana.servired.reportes.aplicacion.Vistas.ImagenSaneada;
import co.edu.javeriana.servired.reportes.aplicacion.Vistas.PaginaReportes;
import co.edu.javeriana.servired.reportes.aplicacion.Vistas.ReporteCercano;
import co.edu.javeriana.servired.reportes.aplicacion.Vistas.Voto;
import co.edu.javeriana.servired.reportes.dominio.IdCorrelacion;
import co.edu.javeriana.servired.reportes.dominio.NuevaDenuncia;
import co.edu.javeriana.servired.reportes.dominio.NuevoReporte;
import co.edu.javeriana.servired.reportes.dominio.Reporte;
import co.edu.javeriana.servired.reportes.dominio.Ubicacion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Operaciones de negocio de Persistencia para el dominio de reportes. Cada
 * escritura que genera un evento lo entrega ya construido (ADR-06) y es
 * idempotente por X-Correlation-Id.
 */
public interface RepositorioReportes {

    Reporte guardar(NuevoReporte reporte, String codigoMunicipio, SobreEvento evento, IdCorrelacion correlacion);

    Optional<Reporte> buscar(UUID idReporte, IdCorrelacion correlacion);

    PaginaReportes listarDeCiudadano(UUID idCiudadano, int pagina, int tamano, IdCorrelacion correlacion);

    List<ReporteCercano> cercanos(Ubicacion centro, int radioMetros, UUID excluirCiudadano, IdCorrelacion correlacion);

    /** @throws co.edu.javeriana.servired.reportes.dominio.ReglaIncumplida si ya estaba restablecido */
    Reporte registrarRestablecimiento(UUID idReporte, Reporte.Restablecimiento r, SobreEvento evento,
                                      IdCorrelacion correlacion);

    /**
     * @throws ConflictoDeVersion si otro voto entró desde la lectura
     * @throws VotoRepetido si el ciudadano ya había votado ese objeto
     */
    Reporte registrarValidacion(UUID idReporte, Voto voto, SobreEvento evento, IdCorrelacion correlacion);

    /** Idempotente por idEvento; si el reporte ya está más adelante no cambia. */
    Reporte aplicarEstadoDeCaso(UUID idReporte, UUID idEvento,
                                co.edu.javeriana.servired.reportes.dominio.EstadoPorCaso cambio,
                                java.time.Instant fecha, IdCorrelacion correlacion);

    Reporte guardarEvidencia(UUID idReporte, UUID idEvidencia, ImagenSaneada imagen, IdCorrelacion correlacion);

    Optional<Archivo> obtenerEvidencia(UUID idReporte, UUID idEvidencia, IdCorrelacion correlacion);

    DenunciaRecibida guardarDenuncia(NuevaDenuncia denuncia, String codigoMunicipio, ImagenSaneada foto,
                                     SobreEvento evento, IdCorrelacion correlacion);

    class ConflictoDeVersion extends RuntimeException {
        public ConflictoDeVersion() {
            super("Las validaciones cambiaron");
        }
    }

    class VotoRepetido extends RuntimeException {
        public VotoRepetido() {
            super("El ciudadano ya validó este objeto");
        }
    }
}
