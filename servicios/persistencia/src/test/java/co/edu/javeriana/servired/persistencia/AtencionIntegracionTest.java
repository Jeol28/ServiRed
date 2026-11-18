package co.edu.javeriana.servired.persistencia;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.jdbc.core.JdbcTemplate;
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

/** Operaciones de Atención (casos y moderación) y el estado del reporte por caso, contra PostgreSQL real. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {"servired.claves.atencion=clave-atencion-de-prueba",
        "servired.claves.reportes=clave-reportes-de-prueba"})
class AtencionIntegracionTest {

    static final String ATENCION = "clave-atencion-de-prueba";
    static final String REPORTES = "clave-reportes-de-prueba";
    static final String ENERGIA_ANDINA = "a0000000-0000-4000-8000-000000000001";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

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

    @Autowired
    JdbcTemplate jdbc;

    private ResultActions enviar(String clave, String ruta, String cuerpo) throws Exception {
        return mvc.perform(post(ruta).header("X-Clave-Servicio", clave)
                .header("X-Correlation-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private ResultActions consultar(String ruta) throws Exception {
        return mvc.perform(get(ruta).header("X-Clave-Servicio", ATENCION)
                .header("X-Correlation-Id", UUID.randomUUID().toString()));
    }

    private UUID abrirCaso(String prioridad, String empresa) throws Exception {
        return abrirCaso(prioridad, empresa, "11001");
    }

    private UUID abrirCaso(String prioridad, String empresa, String municipio) throws Exception {
        UUID idCaso = UUID.randomUUID();
        enviar(ATENCION, "/interno/v1/casos/abrir", """
                {"idCaso":"%s","idReporte":"%s","tipoServicio":"ENERGIA","codigoMunicipio":"%s",
                 "magnitud":"BARRIO","abiertoEn":"2026-11-17T10:00:00Z",%s"prioridad":"%s",
                 "latitud":4.6,"longitud":-74.1,"descripcion":"Sin luz","inicioEstimado":"2026-11-17T09:00:00Z"}"""
                .formatted(idCaso, UUID.randomUUID(), municipio,
                        empresa == null ? "" : "\"idEmpresa\":\"" + empresa + "\",", prioridad))
                .andExpect(status().isCreated());
        return idCaso;
    }

    private UUID crearReporte() throws Exception {
        UUID id = UUID.randomUUID();
        String c = UUID.randomUUID().toString();
        mvc.perform(post("/interno/v1/reportes/guardar").header("X-Clave-Servicio", REPORTES).header("X-Correlation-Id", c)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"reporte":{"idReporte":"%s","idCiudadano":"%s","tipoServicio":"ENERGIA","latitud":4.6,
                          "longitud":-74.1,"codigoMunicipio":"11001","inicioEstimado":"2026-11-17T08:00:00Z",
                          "magnitud":"CUADRA","fechaRegistro":"2026-11-17T09:00:00Z"},
                         "evento":{"idEvento":"%s","tipo":"reporte.creado","version":1,
                          "fechaOcurrencia":"2026-11-17T09:00:00Z","correlationId":"%s","origen":"reportes","datos":{}}}"""
                        .formatted(id, UUID.randomUUID(), UUID.randomUUID(), c))).andExpect(status().isCreated());
        return id;
    }

    @Test
    void resuelveLaEmpresaConCobertura() throws Exception {
        consultar("/interno/v1/catalogo/cobertura?codigoMunicipio=11001&tipoServicio=ENERGIA")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].idEmpresa").value(ENERGIA_ANDINA));
        consultar("/interno/v1/catalogo/cobertura?codigoMunicipio=91001&tipoServicio=ENERGIA")
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void abreElCasoConHistoriaYLoListaPorPrioridad() throws Exception {
        UUID empresa = UUID.fromString("a0000000-0000-4000-8000-000000000005");
        UUID baja = abrirCaso("BAJA", empresa.toString());
        UUID critica = abrirCaso("CRITICA", empresa.toString());

        consultar("/interno/v1/casos/" + critica).andExpect(status().isOk())
                .andExpect(jsonPath("$.historia[0].estado").value("RECIBIDO"))
                .andExpect(jsonPath("$.nombreMunicipio").value("BOGOTÁ, D.C."))
                .andExpect(jsonPath("$.version").value(0));
        consultar("/interno/v1/casos?idEmpresa=" + empresa)
                .andExpect(jsonPath("$.elementos[0].idCaso").value(critica.toString()))
                .andExpect(jsonPath("$.elementos[1].idCaso").value(baja.toString()));
        UUID sinEmpresa = abrirCaso("MEDIA", null);
        String r = consultar("/interno/v1/casos?sinEmpresa=true&tamano=100").andReturn().getResponse().getContentAsString();
        assertThat(r).contains(sinEmpresa.toString()).doesNotContain(critica.toString());
    }

    @Test
    void filtraLaBandejaPorDepartamento() throws Exception {
        UUID medellin = abrirCaso("MEDIA", null, "05001");
        UUID bogota = abrirCaso("MEDIA", null, "11001");
        String antioquia = consultar("/interno/v1/casos?codigoDepartamento=05&tamano=100")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(antioquia).contains(medellin.toString()).doesNotContain(bogota.toString());
        String conMunicipio = consultar("/interno/v1/casos?codigoDepartamento=05&codigoMunicipio=05001&tamano=100")
                .andReturn().getResponse().getContentAsString();
        assertThat(conMunicipio).contains(medellin.toString());
    }

    @Test
    void actualizaConConcurrenciaOptimistaYDejaElEvento() throws Exception {
        UUID caso = abrirCaso("MEDIA", ENERGIA_ANDINA);
        String c = UUID.randomUUID().toString();
        String cuerpo = """
                {"versionEsperada":%d,"estado":"EN_ATENCION","detalleHistoria":"Cuadrilla en camino",
                 "fecha":"2026-11-17T11:00:00Z","eventos":[{"idEvento":"%s","tipo":"caso.actualizado","version":1,
                 "fechaOcurrencia":"2026-11-17T11:00:00Z","correlationId":"%s","origen":"atencion","datos":{}}]}""";

        mvc.perform(post("/interno/v1/casos/" + caso + "/actualizar").header("X-Clave-Servicio", ATENCION)
                        .header("X-Correlation-Id", c).contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo.formatted(0, UUID.randomUUID(), c)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("EN_ATENCION"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.historia[1].detalle").value("Cuadrilla en camino"));
        String c2 = UUID.randomUUID().toString();
        mvc.perform(post("/interno/v1/casos/" + caso + "/actualizar").header("X-Clave-Servicio", ATENCION)
                        .header("X-Correlation-Id", c2).contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo.formatted(0, UUID.randomUUID(), c2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.motivo").value("VERSION"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM infraestructura.outbox WHERE correlation_id IN (?::uuid, ?::uuid)",
                Integer.class, c, c2)).isEqualTo(1);
    }

    @Test
    void verificaSiUnaCuentaEsOperadorDeLaEmpresa() throws Exception {
        UUID operador = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO identidad.cuentas (id, correo, hash_clave, rol, id_empresa, creada_en)
                VALUES (?, ?, 'x', 'OPERADOR', ?::uuid, now())""", operador, operador + "@e.co", ENERGIA_ANDINA);

        consultar("/interno/v1/operadores/" + operador + "?idEmpresa=" + ENERGIA_ANDINA)
                .andExpect(jsonPath("$.operadorDeLaEmpresa").value(true));
        consultar("/interno/v1/operadores/" + operador + "?idEmpresa=a0000000-0000-4000-8000-000000000002")
                .andExpect(jsonPath("$.operadorDeLaEmpresa").value(false));
    }

    @Test
    void moderaUnaDenunciaUnaVezPorVersion() throws Exception {
        UUID denuncia = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO reportes.denuncias (id, tipo_servicio, latitud, longitud, codigo_municipio, descripcion, fecha_recepcion)
                VALUES (?, 'ENERGIA', 4.6, -74.1, '11001', 'Conexión directa al poste', now())""", denuncia);
        String abrir = "{\"idDenuncia\":\"%s\",\"idEmpresa\":\"%s\",\"recibidaEn\":\"2026-11-17T10:00:00Z\"}"
                .formatted(denuncia, ENERGIA_ANDINA);
        enviar(ATENCION, "/interno/v1/moderaciones/abrir", abrir).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.descripcion").value("Conexión directa al poste"));
        enviar(ATENCION, "/interno/v1/moderaciones/abrir", abrir).andExpect(status().isOk());
        String decision = """
                {"versionEsperada":0,"estado":"VALIDADA","nota":"Verificado en terreno","moderadaPor":"%s",
                 "fecha":"2026-11-17T12:00:00Z"}""".formatted(UUID.randomUUID());

        enviar(ATENCION, "/interno/v1/moderaciones/" + denuncia + "/decidir", decision).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("VALIDADA"));
        enviar(ATENCION, "/interno/v1/moderaciones/" + denuncia + "/decidir", decision).andExpect(status().isConflict());
        consultar("/interno/v1/moderaciones?estado=VALIDADA&idEmpresa=" + ENERGIA_ANDINA)
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void elEstadoDelReportePorCasoEsIdempotenteYNoRetrocede() throws Exception {
        UUID reporte = crearReporte();
        String evento = """
                {"idEvento":"%s","estadoNuevo":"%s","desde":%s,"detalle":"%s","fecha":"2026-11-17T11:00:00Z"}""";
        UUID idEvento = UUID.randomUUID();
        String enAtencion = evento.formatted(idEvento, "EN_ATENCION", "[\"RECIBIDO\"]", "El operador atiende el caso");

        enviar(REPORTES, "/interno/v1/reportes/" + reporte + "/estado-por-caso", enAtencion)
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("EN_ATENCION"));
        enviar(REPORTES, "/interno/v1/reportes/" + reporte + "/estado-por-caso", enAtencion)
                .andExpect(jsonPath("$.historia.length()").value(2));
        enviar(REPORTES, "/interno/v1/reportes/" + reporte + "/estado-por-caso",
                evento.formatted(UUID.randomUUID(), "RESTABLECIDO", "[\"RECIBIDO\",\"EN_ATENCION\"]", "Resuelto"))
                .andExpect(jsonPath("$.estado").value("RESTABLECIDO"));
        // Un EN_ATENCION tardío no hace retroceder el reporte.
        enviar(REPORTES, "/interno/v1/reportes/" + reporte + "/estado-por-caso",
                evento.formatted(UUID.randomUUID(), "EN_ATENCION", "[\"RECIBIDO\"]", "tardío"))
                .andExpect(jsonPath("$.estado").value("RESTABLECIDO"))
                .andExpect(jsonPath("$.historia.length()").value(3));
        // Resuelto por el operador sin fecha: el ciudadano aún registra la hora real.
        String c = UUID.randomUUID().toString();
        mvc.perform(post("/interno/v1/reportes/" + reporte + "/restablecimiento").header("X-Clave-Servicio", REPORTES)
                        .header("X-Correlation-Id", c).contentType(MediaType.APPLICATION_JSON).content("""
                                {"fechaRestablecimiento":"2026-11-17T10:30:00Z","duracionMinutos":150,"evento":
                                 {"idEvento":"%s","tipo":"restablecimiento.registrado","version":1,
                                  "fechaOcurrencia":"2026-11-17T10:30:00Z","correlationId":"%s","origen":"reportes","datos":{}}}"""
                                .formatted(UUID.randomUUID(), c)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.duracionMinutos").value(150));
    }
}
