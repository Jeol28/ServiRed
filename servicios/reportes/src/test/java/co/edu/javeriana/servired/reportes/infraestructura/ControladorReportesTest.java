package co.edu.javeriana.servired.reportes.infraestructura;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.javeriana.servired.reportes.aplicacion.ConsultarReporte;
import co.edu.javeriana.servired.reportes.aplicacion.CrearReporte;
import co.edu.javeriana.servired.reportes.aplicacion.PersistenciaEnMemoria;
import co.edu.javeriana.servired.reportes.aplicacion.SobreEvento;
import co.edu.javeriana.servired.reportes.dominio.IdCorrelacion;
import co.edu.javeriana.servired.reportes.dominio.NuevoReporte;
import co.edu.javeriana.servired.reportes.dominio.Reporte;
import co.edu.javeriana.servired.reportes.infraestructura.persistencia.PersistenciaNoDisponible;
import co.edu.javeriana.servired.reportes.infraestructura.seguridad.ConfiguracionSeguridad;
import co.edu.javeriana.servired.reportes.infraestructura.web.CiudadanoActual;
import co.edu.javeriana.servired.reportes.infraestructura.web.ControladorReportes;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** API pública de reportes con los casos de uso reales y Persistencia en memoria. */
@WebMvcTest(ControladorReportes.class)
@Import({CiudadanoActual.class, ConfiguracionSeguridad.class, ControladorReportesTest.Casos.class})
class ControladorReportesTest {

    @TestConfiguration
    static class Casos {
        @Bean
        PersistenciaCaible persistencia() {
            return new PersistenciaCaible();
        }

        @Bean
        CrearReporte crearReporte(PersistenciaCaible p) {
            return new CrearReporte(p, p, Clock.systemUTC());
        }

        @Bean
        ConsultarReporte consultarReporte(PersistenciaCaible p) {
            return new ConsultarReporte(p);
        }
    }

    static class PersistenciaCaible extends PersistenciaEnMemoria {
        @Override
        public Reporte guardar(NuevoReporte r, String m, SobreEvento e, IdCorrelacion c) {
            if (!disponible) {
                throw new PersistenciaNoDisponible("caída", null);
            }
            return super.guardar(r, m, e, c);
        }
    }

    @Autowired
    MockMvc mvc;

    private final UUID ciudadano = UUID.randomUUID();

    /** JWT ya validado de un usuario con el rol indicado (el decoder no interviene). */
    private static RequestPostProcessor usuario(UUID sub, String rol) {
        return jwt().jwt(j -> j.subject(sub.toString()).claim("rol", rol))
                .authorities(new SimpleGrantedAuthority("ROLE_" + rol));
    }

    private RequestPostProcessor ciudadano() {
        return usuario(ciudadano, "CIUDADANO");
    }

    @Autowired
    PersistenciaCaible persistencia;

    @BeforeEach
    void reiniciar() {
        persistencia.disponible = true;
    }

    private static String cuerpo(String inicio) {
        return """
                {"tipoServicio":"ENERGIA","ubicacion":{"latitud":4.6287,"longitud":-74.0648},
                 "inicioEstimado":"%s","magnitud":"CUADRA","descripcion":"Sin luz"}""".formatted(inicio);
    }

    @Test
    void creaElReporteYRespondeConLocation() throws Exception {
        String correlacion = UUID.randomUUID().toString();

        mvc.perform(post("/api/v1/reportes").with(ciudadano()).header("X-Correlation-Id", correlacion)
                        .contentType(MediaType.APPLICATION_JSON).content(cuerpo(Instant.now().minusSeconds(3600).toString())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/reportes/")))
                .andExpect(header().string("X-Correlation-Id", correlacion))
                .andExpect(jsonPath("$.estado").value("RECIBIDO"))
                .andExpect(jsonPath("$.codigoMunicipio").value("11001"))
                .andExpect(jsonPath("$.ubicacion.latitud").value(4.6287))
                .andExpect(jsonPath("$.historia[0].estado").value("RECIBIDO"))
                .andExpect(jsonPath("$.idCiudadano").doesNotExist());
    }

    @Test
    void aceptaFechasConZonaHorariaDeColombia() throws Exception {
        String inicio = java.time.OffsetDateTime.now(java.time.ZoneOffset.ofHours(-5)).minusHours(1).toString();

        mvc.perform(post("/api/v1/reportes").with(ciudadano()).contentType(MediaType.APPLICATION_JSON).content(cuerpo(inicio)))
                .andExpect(status().isCreated());
    }

    @Test
    void devuelveLosErroresDeValidacionComoProblemDetails() throws Exception {
        mvc.perform(post("/api/v1/reportes").with(ciudadano()).contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo(Instant.now().plusSeconds(7200).toString())))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.errores[0].campo").value("inicioEstimado"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void rechazaValoresFueraDelCatalogo() throws Exception {
        mvc.perform(post("/api/v1/reportes").with(ciudadano()).contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo(Instant.now().toString()).replace("ENERGIA", "TELEFONIA")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("tipoServicio"));
    }

    @Test
    void rechazaCamposNoDeclarados() throws Exception {
        mvc.perform(post("/api/v1/reportes").with(ciudadano()).contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo(Instant.now().toString()).replace("{\"tipo", "{\"idCiudadano\":\"x\",\"tipo")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void respondeServicioNoDisponibleSiPersistenciaEstaCaida() throws Exception {
        persistencia.disponible = false;

        mvc.perform(post("/api/v1/reportes").with(ciudadano()).contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo(Instant.now().toString())))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void sinTokenResponde401YConOtroRol403() throws Exception {
        mvc.perform(get("/api/v1/reportes/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", "application/problem+json"));
        mvc.perform(post("/api/v1/reportes").with(usuario(UUID.randomUUID(), "OPERADOR"))
                        .contentType(MediaType.APPLICATION_JSON).content(cuerpo(Instant.now().toString())))
                .andExpect(status().isForbidden());
    }

    @Test
    void otroCiudadanoNoVeElReporte() throws Exception {
        String location = mvc.perform(post("/api/v1/reportes").with(ciudadano()).contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo(Instant.now().toString())))
                .andReturn().getResponse().getHeader("Location");

        mvc.perform(get(location).with(usuario(UUID.randomUUID(), "CIUDADANO"))).andExpect(status().isNotFound());
    }

    @Test
    void consultaElReporteCreado() throws Exception {
        String location = mvc.perform(post("/api/v1/reportes").with(ciudadano()).contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo(Instant.now().toString())))
                .andReturn().getResponse().getHeader("Location");

        mvc.perform(get(location).with(ciudadano())).andExpect(status().isOk()).andExpect(jsonPath("$.magnitud").value("CUADRA"));
        mvc.perform(get("/api/v1/reportes/" + UUID.randomUUID()).with(ciudadano())).andExpect(status().isNotFound());
    }
}
