package co.edu.javeriana.servired.persistencia.aplicacion.puertos;

import co.edu.javeriana.servired.persistencia.dominio.Fase4.DenunciaGuardada;
import co.edu.javeriana.servired.persistencia.dominio.Fase4.DenunciaNueva;
import co.edu.javeriana.servired.persistencia.dominio.Fase4.PaginaReportes;
import co.edu.javeriana.servired.persistencia.dominio.Fase4.ReporteCercano;
import co.edu.javeriana.servired.persistencia.dominio.Fase4.Restablecimiento;
import co.edu.javeriana.servired.persistencia.dominio.Fase4.VotoNuevo;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tablas del dominio de reportes añadidas en la fase 4. */
public interface RepositorioFase4 {

    boolean existeReporte(UUID idReporte);

    /** Bloquea la fila del reporte hasta el fin de la transacción. */
    boolean bloquearReporte(UUID idReporte);

    /** Solo aplica si el reporte está en RECIBIDO o EN_ATENCION. */
    boolean registrarRestablecimiento(UUID idReporte, Restablecimiento r);

    /** Incrementa la versión de validaciones solo si coincide con la esperada. */
    /** Cambia el estado solo si el actual está en {@code desde}; devuelve false si no aplicaba. */
    boolean cambiarEstado(UUID idReporte, String estadoNuevo, List<String> desde);

    boolean avanzarVersionValidaciones(UUID idReporte, int versionEsperada);

    /** @return false si el ciudadano ya había votado ese objeto */
    boolean insertarVoto(UUID idReporte, VotoNuevo voto);

    void agregarHistoria(UUID idReporte, String estado, Instant fecha, String detalle);

    PaginaReportes listarDeCiudadano(UUID idCiudadano, int pagina, int tamano);

    List<ReporteCercano> cercanos(double latitud, double longitud, int radioMetros, UUID excluirCiudadano, int limite);

    int contarEvidencias(UUID idReporte);

    void insertarEvidencia(UUID idEvidencia, UUID idReporte, UUID idDenuncia, String clave, String tipo, int tamano,
                           String sha256, Instant creadaEn);

    Optional<EvidenciaReferencia> evidenciaDeReporte(UUID idReporte, UUID idEvidencia);

    void insertarDenuncia(DenunciaNueva d);

    Optional<DenunciaGuardada> buscarDenuncia(UUID idDenuncia);

    record EvidenciaReferencia(String clave, String tipoContenido) {
    }
}
