package co.edu.javeriana.servired.identidad.dominio;

/**
 * Política de claves: entre 10 y 64 caracteres (y a lo sumo 72 bytes, el límite
 * de bcrypt) y distinta del correo. Se privilegia la longitud sobre reglas de
 * composición (NIST SP 800-63B).
 */
public final class PoliticaClave {

    public static final int MINIMO = 10;
    public static final int MAXIMO = 64;
    private static final int MAXIMO_BYTES = 72;

    private PoliticaClave() {
    }

    public static void validar(String clave, Correo correo) {
        if (clave == null || clave.length() < MINIMO) {
            throw new DatosInvalidos("clave", "debe tener al menos " + MINIMO + " caracteres");
        }
        if (clave.length() > MAXIMO
                || clave.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAXIMO_BYTES) {
            throw new DatosInvalidos("clave", "debe tener máximo " + MAXIMO + " caracteres");
        }
        if (correo != null && clave.equalsIgnoreCase(correo.valor())) {
            throw new DatosInvalidos("clave", "no puede ser igual al correo");
        }
    }
}
