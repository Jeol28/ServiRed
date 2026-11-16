package co.edu.javeriana.servired.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.javeriana.servired.gateway.dominio.IdCorrelacion;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
@AutoConfigureWebTestClient
@AutoConfigureObservability
class GatewayIntegracionTest {

    @Autowired
    WebTestClient cliente;

    @LocalManagementPort
    int puertoGestion;

    private WebTestClient gestion() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + puertoGestion).build();
    }

    @Test
    void readyzResponde200EnElPuertoPublico() {
        cliente.get().uri("/readyz").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void lasMetricasNoSeExponenEnElPuertoPublico() {
        cliente.get().uri("/actuator/prometheus").exchange().expectStatus().isNotFound();
        gestion().get().uri("/actuator/prometheus").exchange().expectStatus().isOk();
        gestion().get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
    }

    @Test
    void devuelveElIdDeCorrelacionRecibido() {
        String id = UUID.randomUUID().toString();

        cliente.get().uri("/readyz").header(IdCorrelacion.CABECERA, id).exchange()
                .expectHeader().valueEquals(IdCorrelacion.CABECERA, id);
    }

    @Test
    void generaIdDeCorrelacionSiNoLlega() {
        String generado = cliente.get().uri("/readyz").exchange()
                .returnResult(String.class).getResponseHeaders().getFirst(IdCorrelacion.CABECERA);

        assertThat(UUID.fromString(generado)).isNotNull();
    }

    @Test
    void unaRutaNoDeclaradaSeNiega() {
        cliente.get().uri("/interno/v1/reportes/x").exchange().expectStatus().isUnauthorized();
    }
}
