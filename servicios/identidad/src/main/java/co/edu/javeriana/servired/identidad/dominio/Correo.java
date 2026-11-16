package co.edu.javeriana.servired.identidad.dominio;

import java.util.Locale;
import java.util.regex.Pattern;

/** Correo normalizado (sin espacios y en minúsculas). Es un dato personal: no se registra en logs. */
public record Correo(String valor) {

    private static final Pattern FORMA = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    public static final int LONGITUD_MAXIMA = 254;

    public Correo {
        if (valor == null || valor.length() > LONGITUD_MAXIMA || !FORMA.matcher(valor).matches()) {
            throw new DatosInvalidos("correo", "no es un correo válido");
        }
    }

    public static Correo de(String recibido) {
        return new Correo(recibido == null ? null : recibido.strip().toLowerCase(Locale.ROOT));
    }

    @Override
    public String toString() {
        return "Correo[***]";
    }
}
