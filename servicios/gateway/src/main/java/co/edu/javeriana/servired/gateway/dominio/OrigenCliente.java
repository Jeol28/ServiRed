package co.edu.javeriana.servired.gateway.dominio;

import java.net.InetAddress;
import java.util.function.Predicate;

/**
 * Determina la dirección del cliente para el límite de abuso. Detrás de un
 * proxy propio (nginx de los portales) todas las peticiones llegan desde la
 * misma IP; solo en ese caso se acepta X-Forwarded-For, y se toma la última
 * entrada, que es la que agregó el proxy. Si la cabecera viene de cualquier
 * otro origen se ignora: un cliente no puede falsificarla para evadir el límite.
 */
public final class OrigenCliente {

    private static final int LONGITUD_MAXIMA = 64;

    private OrigenCliente() {
    }

    public static String direccion(InetAddress remota, String reenviadoPor, Predicate<InetAddress> esProxyConfiable) {
        String directa = remota == null ? "desconocida" : remota.getHostAddress();
        if (remota == null || reenviadoPor == null || reenviadoPor.isBlank() || !esProxyConfiable.test(remota)) {
            return directa;
        }
        String[] saltos = reenviadoPor.split(",");
        String ultima = saltos[saltos.length - 1].trim();
        if (ultima.isEmpty()) {
            return directa;
        }
        return ultima.length() > LONGITUD_MAXIMA ? ultima.substring(0, LONGITUD_MAXIMA) : ultima;
    }
}
