package co.edu.javeriana.servired.identidad.dominio;

import java.util.List;

/** Los datos de la solicitud no cumplen las reglas de identidad. */
public class DatosInvalidos extends RuntimeException {

    private final List<ErrorDeCampo> errores;

    public DatosInvalidos(List<ErrorDeCampo> errores) {
        super("Datos inválidos: " + errores.stream().map(ErrorDeCampo::campo).toList());
        this.errores = List.copyOf(errores);
    }

    public DatosInvalidos(String campo, String mensaje) {
        this(List.of(new ErrorDeCampo(campo, mensaje)));
    }

    public List<ErrorDeCampo> errores() {
        return errores;
    }
}
