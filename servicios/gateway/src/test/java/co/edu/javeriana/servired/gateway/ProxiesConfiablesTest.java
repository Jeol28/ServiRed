package co.edu.javeriana.servired.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.javeriana.servired.gateway.infraestructura.ProxiesConfiables;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProxiesConfiablesTest {

    static InetAddress ip(String literal) throws UnknownHostException {
        return InetAddress.getByName(literal);
    }

    static class RelojMovil extends Clock {
        Instant ahora = Instant.parse("2026-11-17T12:00:00Z");

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return ahora; }
    }

    @Test
    void sinNombresNoConfiaEnNadie() throws Exception {
        assertThat(new ProxiesConfiables("").test(ip("127.0.0.1"))).isFalse();
    }

    @Test
    void resuelveDeFormaPerezosaYRefrescaCuandoCambiaLaIp() throws Exception {
        Map<String, String> dns = new HashMap<>();
        AtomicInteger consultas = new AtomicInteger();
        RelojMovil reloj = new RelojMovil();
        var proxies = new ProxiesConfiables("portal-publico, portal-operador", nombre -> {
            consultas.incrementAndGet();
            String d = dns.get(nombre);
            if (d == null) {
                throw new UnknownHostException(nombre);
            }
            return new InetAddress[]{ip(d)};
        }, reloj);

        // Aún no existe ningún portal.
        assertThat(proxies.test(ip("172.20.0.5"))).isFalse();

        dns.put("portal-publico", "172.20.0.5");
        reloj.ahora = reloj.ahora.plus(Duration.ofSeconds(31));
        assertThat(proxies.test(ip("172.20.0.5"))).isTrue();
        assertThat(proxies.test(ip("172.20.0.6"))).isFalse();

        // Dentro de la vigencia no vuelve a consultar el DNS.
        int antes = consultas.get();
        proxies.test(ip("172.20.0.5"));
        assertThat(consultas.get()).isEqualTo(antes);

        // El contenedor se recreó con otra IP.
        dns.put("portal-publico", "172.20.0.9");
        reloj.ahora = reloj.ahora.plus(Duration.ofSeconds(31));
        assertThat(proxies.test(ip("172.20.0.5"))).isFalse();
        assertThat(proxies.test(ip("172.20.0.9"))).isTrue();
    }
}
