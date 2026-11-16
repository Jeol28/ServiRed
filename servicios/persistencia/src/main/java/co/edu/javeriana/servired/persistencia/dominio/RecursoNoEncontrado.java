package co.edu.javeriana.servired.persistencia.dominio;

/** El recurso sobre el que se pide la operación no existe (404). */
public class RecursoNoEncontrado extends RuntimeException {

    public RecursoNoEncontrado(String mensaje) {
        super(mensaje);
    }
}
