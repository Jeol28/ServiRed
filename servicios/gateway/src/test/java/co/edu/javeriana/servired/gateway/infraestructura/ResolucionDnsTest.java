package co.edu.javeriana.servired.gateway.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.NameResolverProvider;

class ResolucionDnsTest {

    @Test
    void elClienteDelGatewayGuardaElDnsComoMucho5Segundos() {
        HttpClient cliente = new ResolucionDns().cacheDnsCorta().customize(HttpClient.create());

        NameResolverProvider resolvedor = cliente.configuration().getNameResolverProvider();
        assertThat(resolvedor).isNotNull();
        assertThat(resolvedor.cacheMaxTimeToLive()).isEqualTo(Duration.ofSeconds(5));
        assertThat(resolvedor.cacheNegativeTimeToLive()).isEqualTo(Duration.ofSeconds(1));
    }
}
