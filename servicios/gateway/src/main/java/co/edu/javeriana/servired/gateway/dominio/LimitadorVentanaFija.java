package co.edu.javeriana.servired.gateway.dominio;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Límite de solicitudes por clave en ventanas fijas (RNF-SEG5). La clave es un
 * hash del cliente que solo vive en memoria y se descarta al cambiar de
 * ventana. Es intencionalmente generoso para no bloquear a comunidades que
 * comparten una IP (p. ej. una red comunitaria).
 */
public final class LimitadorVentanaFija {

    private final int maximo;
    private final Duration ventana;
    private final Clock reloj;
    private final Map<String, int[]> conteos = new ConcurrentHashMap<>();
    private volatile long ventanaActual;

    public LimitadorVentanaFija(int maximo, Duration ventana, Clock reloj) {
        if (maximo < 1 || ventana.isNegative() || ventana.isZero()) {
            throw new IllegalArgumentException("Límite o ventana inválidos");
        }
        this.maximo = maximo;
        this.ventana = ventana;
        this.reloj = reloj;
    }

    /** @return resultado con los segundos que faltan para que se libere la ventana */
    public Resultado intentar(String clave) {
        long ahora = reloj.millis();
        long numero = ahora / ventana.toMillis();
        if (numero != ventanaActual) {
            synchronized (this) {
                if (numero != ventanaActual) {
                    conteos.clear();
                    ventanaActual = numero;
                }
            }
        }
        int usados = conteos.computeIfAbsent(clave, k -> new int[1])[0]++ + 1;
        long restanteMs = (numero + 1) * ventana.toMillis() - ahora;
        return new Resultado(usados <= maximo, Math.max(1, (restanteMs + 999) / 1000));
    }

    public record Resultado(boolean permitido, long segundosParaReintentar) {
    }
}
