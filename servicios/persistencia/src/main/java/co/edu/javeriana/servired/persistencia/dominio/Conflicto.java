package co.edu.javeriana.servired.persistencia.dominio;

/**
 * La operación no se aplicó por el estado actual de los datos. El {@code motivo}
 * es un código estable que el servicio dueño interpreta (409 con "motivo").
 */
public class Conflicto extends RuntimeException {

    private final String motivo;

    public Conflicto(String motivo, String mensaje) {
        super(mensaje);
        this.motivo = motivo;
    }

    public String motivo() {
        return motivo;
    }
}
