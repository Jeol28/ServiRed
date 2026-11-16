package co.edu.javeriana.servired.reportes.dominio;

/** La solicitud es válida en forma pero una regla de negocio impide aplicarla (409). */
public class ReglaIncumplida extends RuntimeException {

    private final String motivo;

    public ReglaIncumplida(String motivo, String mensaje) {
        super(mensaje);
        this.motivo = motivo;
    }

    public String motivo() {
        return motivo;
    }
}
