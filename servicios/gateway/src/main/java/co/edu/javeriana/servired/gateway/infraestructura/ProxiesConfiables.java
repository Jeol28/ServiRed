package co.edu.javeriana.servired.gateway.infraestructura;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Proxies inversos propios (los nginx de los portales web) por nombre de host.
 * Se resuelven de forma perezosa y se refrescan cada 30 s, porque en Docker las
 * IP cambian al recrear un contenedor y los portales arrancan después del gateway.
 */
@Component
public class ProxiesConfiables implements Predicate<InetAddress> {

    /** Resuelve un nombre a sus direcciones; inyectable para las pruebas. */
    public interface Resolutor {
        InetAddress[] resolver(String nombre) throws UnknownHostException;
    }

    private static final Duration VIGENCIA = Duration.ofSeconds(30);

    private final List<String> nombres;
    private final Resolutor resolutor;
    private final Clock reloj;
    private volatile Set<InetAddress> direcciones = Set.of();
    private volatile Instant vence = Instant.MIN;

    @Autowired
    public ProxiesConfiables(@Value("${servired.limites.proxies-confiables:}") String nombres) {
        this(nombres, InetAddress::getAllByName, Clock.systemUTC());
    }

    public ProxiesConfiables(String nombres, Resolutor resolutor, Clock reloj) {
        this.nombres = Arrays.stream(nombres.split(",")).map(String::trim).filter(n -> !n.isEmpty()).toList();
        this.resolutor = resolutor;
        this.reloj = reloj;
    }

    @Override
    public boolean test(InetAddress direccion) {
        if (nombres.isEmpty()) {
            return false;
        }
        Instant ahora = reloj.instant();
        if (ahora.isAfter(vence)) {
            Set<InetAddress> nuevas = new HashSet<>();
            for (String nombre : nombres) {
                try {
                    nuevas.addAll(Arrays.asList(resolutor.resolver(nombre)));
                } catch (UnknownHostException e) {
                    // El portal aún no existe (o no se desplegó): no se confía en él.
                }
            }
            direcciones = Set.copyOf(nuevas);
            vence = ahora.plus(VIGENCIA);
        }
        return direcciones.contains(direccion);
    }
}
