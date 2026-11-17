package co.edu.javeriana.servired.gateway.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class OrigenClienteTest {

    static final InetAddress PROXY = ip("172.20.0.5");
    static final InetAddress OTRO = ip("198.51.100.9");

    static InetAddress ip(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void desdeUnProxyConfiableUsaLaUltimaEntradaDeXForwardedFor() {
        assertThat(OrigenCliente.direccion(PROXY, "203.0.113.7", PROXY::equals)).isEqualTo("203.0.113.7");
        // El cliente puede anteponer entradas falsas; la última es la que agregó el proxy.
        assertThat(OrigenCliente.direccion(PROXY, "1.2.3.4, 203.0.113.7", PROXY::equals)).isEqualTo("203.0.113.7");
    }

    @Test
    void ignoraLaCabeceraSiNoVieneDeUnProxyConfiable() {
        assertThat(OrigenCliente.direccion(OTRO, "203.0.113.7", PROXY::equals)).isEqualTo("198.51.100.9");
    }

    @Test
    void sinCabeceraOVaciaUsaLaDireccionDirecta() {
        assertThat(OrigenCliente.direccion(PROXY, null, PROXY::equals)).isEqualTo("172.20.0.5");
        assertThat(OrigenCliente.direccion(PROXY, " , ", PROXY::equals)).isEqualTo("172.20.0.5");
        assertThat(OrigenCliente.direccion(null, "203.0.113.7", d -> true)).isEqualTo("desconocida");
    }

    @Test
    void acotaLaLongitud() {
        assertThat(OrigenCliente.direccion(PROXY, "x".repeat(500), PROXY::equals)).hasSize(64);
    }
}
