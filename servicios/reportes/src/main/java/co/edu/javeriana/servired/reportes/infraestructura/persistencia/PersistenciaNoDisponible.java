package co.edu.javeriana.servired.reportes.infraestructura.persistencia;

/** Persistencia no respondió tras los reintentos (se responde 503). */
public class PersistenciaNoDisponible extends RuntimeException {

    public PersistenciaNoDisponible(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
