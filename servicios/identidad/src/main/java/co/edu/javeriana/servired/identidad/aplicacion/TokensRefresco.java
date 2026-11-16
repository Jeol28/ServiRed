package co.edu.javeriana.servired.identidad.aplicacion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Token de refresco opaco: 256 bits aleatorios. Al cliente se le entrega el
 * valor; en Persistencia solo se guarda su SHA-256, de modo que una fuga de la
 * base de datos no permite renovar sesiones.
 */
public final class TokensRefresco {

    private static final SecureRandom ALEATORIO = new SecureRandom();

    private TokensRefresco() {
    }

    public static Generado generar() {
        byte[] bytes = new byte[32];
        ALEATORIO.nextBytes(bytes);
        String valor = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new Generado(valor, hash(valor));
    }

    public static String hash(String valor) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(valor.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Generado(String valor, String hash) {
    }
}
