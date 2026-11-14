package co.edu.javeriana.servired.persistencia.dominio;

/** La solicitud viola una regla de integridad del negocio (se responde 409/422). */
public class SolicitudRechazada extends RuntimeException {

    public SolicitudRechazada(String mensaje) {
        super(mensaje);
    }
}
