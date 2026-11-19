package co.edu.javeriana.servired.persistencia;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.javeriana.servired.persistencia.soporte.AlmacenDePrueba;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Vista de lectura de Indicadores: proyección en desorden, agregados y hechos, contra PostgreSQL real. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {"servired.claves.indicadores=clave-indicadores-prueba",
        "servired.claves.reportes=clave-reportes-de-prueba"})
class IndicadoresIntegracionTest {

    static final String CLAVE = "clave-indicadores-prueba";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = co.edu.javeriana.servired.persistencia.soporte.BaseDePrueba.crear();

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.1-management-alpine");

    @Container
    static GenericContainer<?> almacen = AlmacenDePrueba.crear();

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry r) {
        AlmacenDePrueba.registrar(almacen, r);
    }

    @Autowired
    MockMvc mvc;

    private ResultActions enviar(String clave, String ruta, String cuerpo) throws Exception {
        return mvc.perform(post(ruta).header("X-Clave-Servicio", clave)
                .header("X-Correlation-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private void vista(String json) throws Exception {
        enviar(CLAVE, "/interno/v1/indicadores/vista", json).andExpect(status().isNoContent());
    }

    private void crear(UUID id, String municipio, String servicio, String magnitud, double lat, double lon)
            throws Exception {
        vista("""
                {"idReporte":"%s","fechaEvento":"2026-11-10T10:00:00Z","tipoServicio":"%s","codigoMunicipio":"%s",
                 "latitud":%s,"longitud":%s,"magnitud":"%s","inicioEstimado":"2026-11-10T08:00:00Z",
                 "fechaRegistro":"2026-11-10T10:00:00Z"}""".formatted(id, servicio, municipio, lat, lon, magnitud));
    }

    private static final String FILTRO = """
            {"desde":"2026-11-01T00:00:00Z","hasta":"2026-12-01T00:00:00Z"%s}""";

    @Test
    void laProyeccionToleraElDesordenYNoRetrocede() throws Exception {
        UUID id = UUID.randomUUID();
        // El restablecimiento y una validación llegan antes que reporte.creado.
        vista("""
                {"idReporte":"%s","fechaEvento":"2026-11-10T12:00:00Z","fechaRestablecimiento":"2026-11-10T11:00:00Z",
                 "duracionMinutos":180}""".formatted(id));
        vista("""
                {"idReporte":"%s","fechaEvento":"2026-11-10T13:00:00Z","validado":true,"confirmacionesNetas":3}"""
                .formatted(id));
        crear(id, "54001", "GAS", "SECTOR", 7.89, -72.50);
        // Una validación más antigua llega tarde: no pisa la reciente.
        vista("""
                {"idReporte":"%s","fechaEvento":"2026-11-10T12:30:00Z","validado":false,"confirmacionesNetas":1}"""
                .formatted(id));

        enviar(CLAVE, "/interno/v1/indicadores/hechos",
                "{\"filtro\":" + FILTRO.formatted(",\"codigoMunicipio\":\"54001\"") + ",\"limite\":10}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.elementos[0].duracionMinutos").value(180))
                .andExpect(jsonPath("$.elementos[0].validado").value(true))
                .andExpect(jsonPath("$.elementos[0].confirmacionesNetas").value(3))
                .andExpect(jsonPath("$.elementos[0].tipoServicio").value("GAS"))
                .andExpect(jsonPath("$.elementos[0].nombreMunicipio").value("SAN JOSÉ DE CÚCUTA"));
    }

    @Test
    void agregaPorMunicipioConDuracionYSeveridad() throws Exception {
        for (int i = 0; i < 4; i++) {
            UUID id = UUID.randomUUID();
            crear(id, "73001", "ACUEDUCTO", i < 2 ? "BARRIO" : "VIVIENDA", 4.4335 + i * 0.001, -75.2345);
            vista("""
                    {"idReporte":"%s","fechaEvento":"2026-11-10T15:00:00Z","fechaRestablecimiento":"2026-11-10T12:00:00Z",
                     "duracionMinutos":%d}""".formatted(id, 60 * (i + 1)));
        }

        enviar(CLAVE, "/interno/v1/indicadores/agregados", """
                {"filtro":%s,"agrupacion":"MUNICIPIO"}""".formatted(FILTRO.formatted(",\"codigoMunicipio\":\"73001\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].codigo").value("73001"))
                .andExpect(jsonPath("$[0].departamento").value("TOLIMA"))
                .andExpect(jsonPath("$[0].reportes").value(4))
                .andExpect(jsonPath("$[0].duracionMediana").value(150.0))
                .andExpect(jsonPath("$[0].barrio").value(2))
                .andExpect(jsonPath("$[0].resueltos").value(4));
        enviar(CLAVE, "/interno/v1/indicadores/agregados", """
                {"filtro":%s,"agrupacion":"CELDA","celdaGrados":0.01}""".formatted(FILTRO.formatted(",\"codigoMunicipio\":\"73001\"")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].celdaLatitud").value(org.hamcrest.Matchers.closeTo(4.435, 0.0001)));
        enviar(CLAVE, "/interno/v1/indicadores/agregados", """
                {"filtro":%s,"agrupacion":"MUNICIPIO_SERVICIO_MES"}""".formatted(FILTRO.formatted(",\"codigoMunicipio\":\"73001\"")))
                .andExpect(jsonPath("$[0].mes").value("2026-11-01T00:00:00Z"))
                .andExpect(jsonPath("$[0].tipoServicio").value("ACUEDUCTO"));
    }

    @Test
    void filtraActivosYResueltosYRechazaPeriodosEnormes() throws Exception {
        UUID activo = UUID.randomUUID();
        crear(activo, "66001", "ENERGIA", "CUADRA", 4.81, -75.69);
        UUID resuelto = UUID.randomUUID();
        crear(resuelto, "66001", "ENERGIA", "CUADRA", 4.81, -75.69);
        vista("""
                {"idReporte":"%s","fechaEvento":"2026-11-10T12:00:00Z","estadoCaso":"RESUELTO","prioridad":"MEDIA",
                 "resueltoEn":"2026-11-10T12:00:00Z"}""".formatted(resuelto));

        enviar(CLAVE, "/interno/v1/indicadores/agregados", """
                {"filtro":%s,"agrupacion":"NINGUNA"}""".formatted(FILTRO.formatted(",\"codigoMunicipio\":\"66001\",\"situacion\":\"ACTIVO\"")))
                .andExpect(jsonPath("$[0].reportes").value(1));
        enviar(CLAVE, "/interno/v1/indicadores/agregados", """
                {"filtro":%s,"agrupacion":"NINGUNA"}""".formatted(FILTRO.formatted(",\"codigoMunicipio\":\"66001\",\"situacion\":\"RESUELTO\"")))
                .andExpect(jsonPath("$[0].reportes").value(1))
                .andExpect(jsonPath("$[0].tiempoResolucionPromedio").value(120.0));
        enviar(CLAVE, "/interno/v1/indicadores/agregados", """
                {"filtro":{"desde":"2020-01-01T00:00:00Z","hasta":"2026-12-01T00:00:00Z"},"agrupacion":"NINGUNA"}""")
                .andExpect(status().isConflict());
    }

    @Test
    void soloIndicadoresUsaLaVista() throws Exception {
        enviar("clave-reportes-de-prueba", "/interno/v1/indicadores/agregados",
                "{\"filtro\":" + FILTRO.formatted("") + ",\"agrupacion\":\"NINGUNA\"}").andExpect(status().isForbidden());
        mvc.perform(get("/interno/v1/indicadores/estado").header("X-Clave-Servicio", CLAVE)
                        .header("X-Correlation-Id", UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reportes").isNumber());
    }
}
