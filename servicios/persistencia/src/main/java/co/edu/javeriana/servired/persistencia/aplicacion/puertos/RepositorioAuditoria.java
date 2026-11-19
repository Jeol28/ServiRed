package co.edu.javeriana.servired.persistencia.aplicacion.puertos;

import co.edu.javeriana.servired.persistencia.dominio.Auditoria.Rango;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.RegistroArchivado;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.Segmento;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.Filtro;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.Pagina;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.RegistroNuevo;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.Verificacion;

public interface RepositorioAuditoria {

    /** Encadena el registro al último (bajo bloqueo); false si el evento ya estaba registrado. */
    boolean registrar(RegistroNuevo r);

    Pagina consultar(Filtro f);

    Verificacion verificar();

    // ------------------------------------------------------------ archivado (ADR-29)

    Optional<Segmento> ultimoSegmento();

    List<Segmento> segmentos();

    /**
     * El próximo rango a archivar: desde el primer registro que no está en un segmento hasta el
     * último registrado antes del fin de su mes (UTC) o de {@code limite}, lo que llegue antes;
     * a lo sumo {@code maximo} registros. Vacío si el mes de ese primer registro no terminó
     * antes de {@code limite}.
     */
    Optional<Rango> proximoRango(Instant limite, int maximo);

    List<RegistroArchivado> leer(Rango rango);

    /**
     * Recalcula las huellas de esos registros (con auditoria.huella) y sus enlaces, empezando
     * por {@code anteriorEsperado}. Devuelve la secuencia del primero que no coincide, o null.
     */
    Long primeraRota(List<RegistroArchivado> registros, String anteriorEsperado);

    /** Inserta el segmento y borra de la base los registros que cubre; devuelve cuántos borró. */
    int archivar(Segmento segmento);
}
