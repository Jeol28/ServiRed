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
import co.edu.javeriana.servired.reportes.infraestructura.web.ControladorDenuncias;
import co.edu.javeriana.servired.reportes.aplicacion.GestionEvidencias;
import co.edu.javeriana.servired.reportes.aplicacion.ParticipacionCiudadana;
import co.edu.javeriana.servired.reportes.aplicacion.RecibirDenuncia;
import co.edu.javeriana.servired.reportes.dominio.ReglasValidacion;
import co.edu.javeriana.servired.reportes.infraestructura.imagenes.SaneadorImagenesImageIO;
import org.springframework.mock.web.MockMultipartFile;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
@WebMvcTest({ControladorReportes.class, ControladorDenuncias.class})
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

        @Bean
        ParticipacionCiudadana participacion(PersistenciaCaible p) {
            return new ParticipacionCiudadana(p, new ReglasValidacion(3, 1000), Clock.systemUTC());
        }

        @Bean
        GestionEvidencias gestionEvidencias(PersistenciaCaible p) {
            return new GestionEvidencias(p, new SaneadorImagenesImageIO());
        }

        @Bean
        RecibirDenuncia recibirDenuncia(PersistenciaCaible p) {
            return new RecibirDenuncia(p, p, new SaneadorImagenesImageIO(), Clock.systemUTC());
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

    private String crearYObtenerId() throws Exception {
        String location = mvc.perform(post("/api/v1/reportes").with(ciudadano()).contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo(Instant.now().minusSeconds(7200).toString())))
                .andReturn().getResponse().getHeader("Location");
        return location.substring(location.lastIndexOf('/') + 1);
    }

    @Test
    void elAutorRegistraElRestablecimientoConSuDuracion() throws Exception {
        String id = crearYObtenerId();

        mvc.perform(post("/api/v1/reportes/" + id + "/restablecimiento").with(ciudadano())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fechaRestablecimiento\":\"" + Instant.now().minusSeconds(60) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("RESTABLECIDO"))
                .andExpect(jsonPath("$.duracionMinutos").value(119));
        mvc.perform(post("/api/v1/reportes/" + id + "/restablecimiento").with(ciudadano())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fechaRestablecimiento\":\"" + Instant.now() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.motivo").value("YA_RESTABLECIDO"));
    }

    @Test
    void unVecinoValidaYElAutorNo() throws Exception {
        String id = crearYObtenerId();
        String voto = """
                {"objeto":"CORTE","sentido":"CONFIRMA","ubicacion":{"latitud":4.6290,"longitud":-74.0650}}""";

        mvc.perform(post("/api/v1/reportes/" + id + "/validaciones").with(usuario(UUID.randomUUID(), "CIUDADANO"))
                        .contentType(MediaType.APPLICATION_JSON).content(voto))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.confirmaciones").value(1))
                .andExpect(jsonPath("$.validado").value(false));
        mvc.perform(post("/api/v1/reportes/" + id + "/validaciones").with(ciudadano())
                        .contentType(MediaType.APPLICATION_JSON).content(voto))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.motivo").value("AUTOR"));
    }

    @Test
    void listaMisReportesYLosCercanosDeOtros() throws Exception {
        crearYObtenerId();

        mvc.perform(get("/api/v1/reportes").with(ciudadano())).andExpect(status().isOk())
                .andExpect(jsonPath("$.elementos.length()").value(1));
        mvc.perform(get("/api/v1/reportes/cercanos?latitud=4.6288&longitud=-74.0649")
                        .with(usuario(UUID.randomUUID(), "CIUDADANO")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].distanciaMetros").isNumber())
                .andExpect(jsonPath("$[0].descripcion").doesNotExist());
    }

    @Test
    void adjuntaUnaFotoSaneadaYLaEntregaSoloAlAutor() throws Exception {
        String id = crearYObtenerId();
        byte[] jpeg = SaneadorImagenesTest.conExif(SaneadorImagenesTest.imagen("jpg", 32, 32));

        String respuesta = mvc.perform(multipart("/api/v1/reportes/" + id + "/evidencias")
                        .file(new MockMultipartFile("foto", "foto.jpg", "image/jpeg", jpeg)).with(ciudadano()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.evidencias[0].tipoContenido").value("image/jpeg"))
                .andReturn().getResponse().getContentAsString();
        String url = com.jayway.jsonpath.JsonPath.read(respuesta, "$.evidencias[0].url");

        byte[] guardada = mvc.perform(get(url).with(ciudadano())).andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse().getContentAsByteArray();
        org.assertj.core.api.Assertions.assertThat(new String(guardada, java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("GPSLatitude");
        mvc.perform(get(url).with(usuario(UUID.randomUUID(), "CIUDADANO"))).andExpect(status().isNotFound());
    }

    @Test
    void rechazaArchivosQueNoSonFotos() throws Exception {
        String id = crearYObtenerId();

        mvc.perform(multipart("/api/v1/reportes/" + id + "/evidencias")
                        .file(new MockMultipartFile("foto", "foto.jpg", "image/jpeg", "<?php ?>".getBytes()))
                        .with(ciudadano()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errores[0].campo").value("foto"));
    }

    @Test
    void recibeDenunciasAnonimasSinToken() throws Exception {
        mvc.perform(post("/api/v1/denuncias").contentType(MediaType.APPLICATION_JSON).content("""
                        {"tipoServicio":"ENERGIA","ubicacion":{"latitud":10.98,"longitud":-74.81},
                         "descripcion":"Conexión directa a la red sin medidor"}"""))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.estado").value("PENDIENTE_VALIDACION"))
                .andExpect(jsonPath("$.tieneEvidencia").value(false));
        mvc.perform(multipart("/api/v1/denuncias")
                        .file(new MockMultipartFile("denuncia", "", "application/json", """
                                {"tipoServicio":"GAS","ubicacion":{"latitud":3.42,"longitud":-76.52},
                                 "descripcion":"Manguera conectada a la red de gas"}""".getBytes()))
                        .file(new MockMultipartFile("foto", "f.png", "image/png", SaneadorImagenesTest.imagen("png", 8, 8))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.tieneEvidencia").value(true));
        mvc.perform(post("/api/v1/denuncias").contentType(MediaType.APPLICATION_JSON).content("{\"tipoServicio\":\"GAS\"}"))
                .andExpect(status().isBadRequest());
    }
}
