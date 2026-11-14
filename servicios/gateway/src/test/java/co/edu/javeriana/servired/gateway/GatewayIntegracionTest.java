package co.edu.javeriana.servired.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.javeriana.servired.gateway.dominio.IdCorrelacion;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@AutoConfigureObservability
class GatewayIntegracionTest {

    @Autowired
    WebTestClient cliente;

    @Test
    void healthResponde200() {
        cliente.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void exponeMetricasPrometheus() {
        cliente.get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void devuelveElIdDeCorrelacionRecibido() {
        String id = UUID.randomUUID().toString();

        cliente.get().uri("/actuator/health")
                .header(IdCorrelacion.CABECERA, id)
                .exchange()
                .expectHeader().valueEquals(IdCorrelacion.CABECERA, id);
    }

    @Test
    void generaIdDeCorrelacionSiNoLlega() {
        String generado = cliente.get().uri("/actuator/health")
                .exchange()
                .returnResult(String.class)
                .getResponseHeaders()
                .getFirst(IdCorrelacion.CABECERA);

        assertThat(UUID.fromString(generado)).isNotNull();
    }
}
