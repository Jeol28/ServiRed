package co.edu.javeriana.servired.identidad.infraestructura.persistencia;

/** Persistencia no respondió tras los reintentos (se responde 503). */
public class PersistenciaNoDisponible extends RuntimeException {

    public PersistenciaNoDisponible(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
