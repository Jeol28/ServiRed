package co.edu.javeriana.servired.persistencia.dominio;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Modelos del registro de auditoría (RF-12). La clasificación la decide el servicio Auditoría. */
public final class Auditoria {

    private Auditoria() {
    }

    /** @param detalle JSON ya saneado por Auditoría (sin datos personales) */
    public record RegistroNuevo(UUID idEvento, String tipo, int version, String origen, UUID correlationId,
                                String categoria, String accion, String resultado, UUID actorId, String actorRol,
                                String recursoTipo, String recursoId, String detalle, Instant ocurridoEn) {
    }

    public record Registro(long secuencia, UUID idEvento, String tipo, int version, String origen, UUID correlationId,
                           String categoria, String accion, String resultado, UUID actorId, String actorRol,
                           String recursoTipo, String recursoId, String detalle, Instant ocurridoEn,
                           Instant registradoEn, String hash) {
    }

    /**
     * Filtro del registro; null = sin filtro. {@code actorRol} busca por el rol de quien actuó
     * ({@link #SIN_ROL} = acciones del sistema o anónimas, sin rol) y {@code recursoTipo} por la
     * clase de recurso (CASO, CUENTA, REPORTE…), para no depender de los identificadores.
     */
    public record Filtro(String categoria, String tipo, String accion, UUID actorId, String recursoId, Instant desde,
                         Instant hasta, int pagina, int tamano, String actorRol, String recursoTipo) {
        public static final String SIN_ROL = "SISTEMA";
    }

    public record Pagina(List<Registro> elementos, long total, int pagina, int tamano) {
    }

    /**
     * Integridad de toda la cadena (ADR-29): la parte que sigue en la base, los enlaces entre
     * segmentos archivados y el enlace del primer registro en la base con el último segmento.
     *
     * @param registros         total de la cadena (en la base + archivados)
     * @param primeraRota       secuencia del primer registro en la base cuya huella o enlace no coincide
     * @param segmentoRoto      id del primer segmento que no enlaza con el anterior
     */
    public record Verificacion(long registros, boolean integra, Long primeraRota, String ultimoHash,
                               long registrosEnBase, long registrosArchivados, int segmentos, Long segmentoRoto) {
    }

    /**
     * Un registro tal como se archiva: todo como texto, en la forma exacta en que lo usa
     * auditoria.huella (detalle canónico de JSONB, fechas UTC con microsegundos), para poder
     * recalcular su huella después.
     */
    public record RegistroArchivado(long secuencia, String idEvento, String tipo, int version, String origen,
                                    String correlationId, String categoria, String accion, String resultado,
                                    String actorId, String actorRol, String recursoTipo, String recursoId,
                                    String detalle, String ocurridoEn, String registradoEn, String hashAnterior,
                                    String hash) {
    }

    /** Segmento archivado: índice en auditoria.segmentos; los registros están en el almacén. */
    public record Segmento(Long id, long secuenciaDesde, long secuenciaHasta, int registros,
                           String hashAnteriorInicial, String hashFinal, Instant ocurridoDesde, Instant ocurridoHasta,
                           String claveObjeto, String sha256Archivo, long bytes, Instant archivadoEn) {
    }

    /** Rango contiguo de la cadena listo para archivar. */
    public record Rango(long desde, long hasta) {
    }

    public record ResultadoArchivo(int segmentosNuevos, long registrosArchivados, Instant limite, String detenidoPor) {
    }

    /**
     * Ancla diaria de la cadena (ADR-31): el último registro de ese día y su hash, publicados fuera
     * del sistema para detectar una reescritura de la historia.
     */
    public record Ancla(java.time.LocalDate fecha, long secuencia, String hash, long registros, int segmentos,
                        Instant creadaEn, Instant publicadaEn, String destino, String referencia) {
    }

    /** Resultado de comparar una ancla publicada con la cadena actual. */
    public record ComprobacionAncla(java.time.LocalDate fecha, long secuencia, String hashPublicado, String hashActual,
                                    String ubicacion, boolean coincide) {
    }

    /**
     * Verificación de las anclas. {@code reemplazadas}: las que no coinciden porque un
     * reencadenamiento autorizado recalculó la cadena desde su secuencia (ADR-36); no cuentan como
     * reescritura, pero se informan.
     */
    public record VerificacionAnclas(String fuente, int anclas, boolean coinciden, List<ComprobacionAncla> distintas,
                                     List<ComprobacionAncla> reemplazadas) {
    }

    /** Recálculo de la cadena tras un borrado autorizado (ADR-36). */
    public record Reencadenamiento(long desdeSecuencia, int registrosBorrados, String motivo, String migracion,
                                   Instant realizadoEn) {
    }

    /** Verificación profunda del archivo: descarga cada segmento, compara su SHA-256 y recalcula las huellas. */
    public record VerificacionArchivo(int segmentos, long registros, boolean integro, Long segmentoConProblema,
                                      String problema) {
    }
}
