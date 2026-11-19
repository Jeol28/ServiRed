package co.edu.javeriana.servired.gateway.dominio;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tokens de acceso revocados antes de expirar (ADR-26): por jti y hasta su expiración.
 *
 * <p>Identidad la entrega completa al arrancar y luego por incrementos ({@code desde}). Cada
 * respuesta trae la hora de Identidad ({@code generadaEn}): el próximo incremento pide
 * desde esa hora menos un solapamiento, para no perder una revocación cuya transacción
 * empezó antes y confirmó después. Repetir una entrada no tiene efecto.
 */
public final class ListaRevocacion {

    /** Un token revocado: se rechaza hasta {@code expiraEn}; después ya no sirve de todos modos. */
    public record Entrada(String jti, Instant expiraEn) {
    }

    private final Map<String, Instant> revocados = new ConcurrentHashMap<>();
    private volatile Instant marca;
    private volatile Instant ultimaSincronizacion;

    public boolean revocado(String jti, Instant ahora) {
        if (jti == null) {
            return false;
        }
        Instant expira = revocados.get(jti);
        return expira != null && expira.isAfter(ahora);
    }

    /** Incorpora una respuesta de Identidad y olvida las entradas ya expiradas. */
    public void aplicar(Collection<Entrada> entradas, Instant generadaEn, Instant ahora) {
        entradas.forEach(e -> revocados.merge(e.jti(), e.expiraEn(), (a, b) -> a.isAfter(b) ? a : b));
        revocados.values().removeIf(expira -> !expira.isAfter(ahora));
        marca = generadaEn;
        ultimaSincronizacion = ahora;
    }

    /** null mientras no haya una carga completa: la primera pide toda la lista. */
    public Instant proximoDesde(Duration solapamiento) {
        Instant m = marca;
        return m == null ? null : m.minus(solapamiento);
    }

    public int tamano() {
        return revocados.size();
    }

    /** null si nunca se sincronizó. */
    public Instant ultimaSincronizacion() {
        return ultimaSincronizacion;
    }
}
