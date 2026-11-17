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

    public record Filtro(String categoria, String tipo, String accion, UUID actorId, String recursoId, Instant desde,
                         Instant hasta, int pagina, int tamano) {
    }

    public record Pagina(List<Registro> elementos, long total, int pagina, int tamano) {
    }

    /** @param primeraRota secuencia del primer registro cuya huella no coincide, o null si la cadena es íntegra */
    public record Verificacion(long registros, boolean integra, Long primeraRota, String ultimoHash) {
    }
}
