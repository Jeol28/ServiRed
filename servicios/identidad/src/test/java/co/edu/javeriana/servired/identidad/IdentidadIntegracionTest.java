package co.edu.javeriana.servired.identidad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.javeriana.servired.identidad.dominio.IdCorrelacion;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;


@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability

class IdentidadIntegracionTest {

    @Autowired
    MockMvc mvc;

    @Test
    void readinessEstaUpConSusDependencias() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void exponeMetricasPrometheus() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    }

    @Test
    void propagaElIdDeCorrelacion() throws Exception {
        String id = UUID.randomUUID().toString();

        mvc.perform(get("/actuator/health").header(IdCorrelacion.CABECERA, id))
                .andExpect(header().string(IdCorrelacion.CABECERA, id));
    }

    @Test
    void generaIdDeCorrelacionSiFalta() throws Exception {
        String generado = mvc.perform(get("/actuator/health"))
                .andReturn().getResponse().getHeader(IdCorrelacion.CABECERA);

        assertThat(UUID.fromString(generado)).isNotNull();
    }

}
