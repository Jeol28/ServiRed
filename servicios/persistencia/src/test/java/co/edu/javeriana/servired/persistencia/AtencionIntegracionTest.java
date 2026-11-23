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
    static final String ENERGIA_ANDINA = "619a2e48-402d-5782-9daf-57f16169b157";   // Enel Colombia: energía en Bogotá (ADR-35)
    static final String EAAB = "ece4de53-d488-5b2b-b56d-f1f115e7a882";             // Acueducto de Bogotá
    static final String LIME = "2b79de38-edb8-5beb-a47c-0d690ca57020";             // Limpieza Metropolitana: aseo de Bosa y otras

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

    private ResultActions abrirConEvento(UUID idCaso, UUID idReporte, String correlacion, String tipoEvento)
            throws Exception {
        return mvc.perform(post("/interno/v1/casos/abrir").header("X-Clave-Servicio", ATENCION)
                .header("X-Correlation-Id", correlacion).contentType(MediaType.APPLICATION_JSON).content("""
                        {"idCaso":"%1$s","idReporte":"%2$s","tipoServicio":"ENERGIA","codigoMunicipio":"11001",
                         "magnitud":"BARRIO","abiertoEn":"2026-11-17T10:00:00Z","idEmpresa":"%4$s","prioridad":"ALTA",
                         "eventos":[{"idEvento":"%5$s","tipo":"%6$s","version":1,
                           "fechaOcurrencia":"2026-11-17T10:00:00Z","correlationId":"%3$s","origen":"atencion",
                           "datos":{"idCaso":"%1$s","idReporte":"%2$s","estado":"RECIBIDO","prioridad":"ALTA",
                                    "idEmpresaOperadora":"%4$s","fechaActualizacion":"2026-11-17T10:00:00Z"}}]}"""
                        .formatted(idCaso, idReporte, correlacion, ENERGIA_ANDINA, UUID.randomUUID(), tipoEvento)));
    }

    private int aperturasEnOutbox(UUID idReporte) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM infraestructura.outbox
                WHERE tipo = 'caso.actualizado' AND sobre->'datos'->>'idReporte' = ?""", Integer.class,
                idReporte.toString());
    }

    @Test
    void abrirElCasoDejaSuEventoEnElOutboxUnaSolaVez() throws Exception {
        UUID idReporte = UUID.randomUUID();
        UUID idCaso = UUID.randomUUID();
        String correlacion = UUID.randomUUID().toString();

        abrirConEvento(idCaso, idReporte, correlacion, "caso.actualizado").andExpect(status().isCreated());
        assertThat(aperturasEnOutbox(idReporte)).as("la apertura publica caso.actualizado").isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT sobre->'datos'->>'estado' || '|' || (sobre->'datos'->>'prioridad') FROM infraestructura.outbox
                WHERE tipo = 'caso.actualizado' AND sobre->'datos'->>'idReporte' = ?""", String.class,
                idReporte.toString())).isEqualTo("RECIBIDO|ALTA");

        abrirConEvento(idCaso, idReporte, correlacion, "caso.actualizado").andExpect(status().isOk());
        abrirConEvento(UUID.randomUUID(), idReporte, UUID.randomUUID().toString(), "caso.actualizado")
                .andExpect(status().isOk()).andExpect(jsonPath("$.idCaso").value(idCaso.toString()));
        assertThat(aperturasEnOutbox(idReporte)).as("un reintento o una redelivery no publica otra apertura")
                .isEqualTo(1);
    }

    @Test
    void abrirElCasoNoAdmiteOtrosEventos() throws Exception {
        UUID idReporte = UUID.randomUUID();
        abrirConEvento(UUID.randomUUID(), idReporte, UUID.randomUUID().toString(), "caso.cerrado")
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM atencion.casos WHERE id_reporte = ?", Integer.class,
                idReporte)).as("no abrió el caso").isZero();
    }

    @Test
    void resuelveLaUnicaEmpresaDuenaDeLaZona() throws Exception {
        // Sin ubicación (denuncias por municipio): la de la cabecera.
        consultar("/interno/v1/catalogo/cobertura?codigoMunicipio=11001&tipoServicio=ENERGIA")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idZona").value("11001-U"))
                .andExpect(jsonPath("$.empresa.idEmpresa").value(ENERGIA_ANDINA));
        // Aseo de Bogotá por Áreas de Servicio Exclusivo: Suba es de Área Limpia, Bosa de LIME.
        consultar("/interno/v1/catalogo/cobertura?latitud=4.7417&longitud=-74.0839&tipoServicio=ASEO")
                .andExpect(jsonPath("$.idZona").value("11001-L11"))
                .andExpect(jsonPath("$.nombreZona").value("Suba"))
                .andExpect(jsonPath("$.empresa.nombre").value("ÁREA LIMPIA DISTRITO CAPITAL S.A.S. E.S.P."));
        consultar("/interno/v1/catalogo/cobertura?latitud=4.6185&longitud=-74.1900&tipoServicio=ASEO")
                .andExpect(jsonPath("$.idZona").value("11001-L07"))
                .andExpect(jsonPath("$.empresa.idEmpresa").value(LIME));
        // Una localidad sin dueño propio hereda el de la ciudad: la energía de Suba es de Enel.
        consultar("/interno/v1/catalogo/cobertura?latitud=4.7417&longitud=-74.0839&tipoServicio=ENERGIA")
                .andExpect(jsonPath("$.idZona").value("11001-L11"))
                .andExpect(jsonPath("$.empresa.idEmpresa").value(ENERGIA_ANDINA));
        // Fuera de las cabeceras: la zona rural del municipio (Guasca, Cundinamarca).
        consultar("/interno/v1/catalogo/cobertura?latitud=4.8300&longitud=-73.8300&tipoServicio=ENERGIA")
                .andExpect(jsonPath("$.idZona").value("25322-R"))
                .andExpect(jsonPath("$.empresa.idEmpresa").value(ENERGIA_ANDINA));
        // Sin red de gas natural en Leticia: la dueña es una distribuidora de GLP (ADR-37).
        consultar("/interno/v1/catalogo/cobertura?codigoMunicipio=91001&tipoServicio=GAS")
                .andExpect(jsonPath("$.idZona").value("91001-U"))
                .andExpect(jsonPath("$.empresa.nombre").value("DISTRIBUIDORA RIMAR GAS SAS ESP"));
        // Un área no municipalizada sin prestadores: el departamento en prestación directa.
        consultar("/interno/v1/catalogo/cobertura?codigoMunicipio=91405&tipoServicio=ACUEDUCTO")
                .andExpect(jsonPath("$.empresa.nombre").value("DEPARTAMENTO DE AMAZONAS (PRESTACIÓN DIRECTA)"));
        // Ningún rincón sin dueña: toda cabecera y todo resto rural tiene empresa en los 5 servicios.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM catalogo.zonas z CROSS JOIN catalogo.tipos_servicio t
                WHERE z.tipo IN ('URBANA', 'RURAL') AND NOT EXISTS (
                    SELECT 1 FROM catalogo.coberturas_zona c WHERE c.id_zona = z.id AND c.tipo_servicio = t.codigo)""",
                Integer.class)).isZero();
        consultar("/interno/v1/catalogo/cobertura?tipoServicio=GAS").andExpect(status().isBadRequest());
    }

    @Test
    void listaLasEmpresasRealesSinLasDeEjemplo() throws Exception {
        consultar("/interno/v1/catalogo/empresas")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.idEmpresa == '%s')].nombre".formatted(ENERGIA_ANDINA))
                        .value("ENEL COLOMBIA S.A. E.S.P."))
                .andExpect(jsonPath("$[?(@.nombre =~ /.*ejemplo.*/i)]").isEmpty());
        // Filtros por servicio, departamento y municipio: las dueñas de alguna zona que cumpla.
        consultar("/interno/v1/catalogo/empresas?tipoServicio=ENERGIA&codigoDepartamento=05")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].nombre").value("EMPRESAS PÚBLICAS DE MEDELLÍN E.S.P."))
                .andExpect(jsonPath("$[0].servicios").value(org.hamcrest.Matchers.hasItems("ENERGIA", "GAS")));
        consultar("/interno/v1/catalogo/empresas?tipoServicio=ASEO&codigoMunicipio=11001")
                .andExpect(jsonPath("$[?(@.idEmpresa == '%s')]".formatted(LIME)).exists())
                .andExpect(jsonPath("$[?(@.nombre == 'ÁREA LIMPIA DISTRITO CAPITAL S.A.S. E.S.P.')]").exists())
                .andExpect(jsonPath("$[?(@.idEmpresa == '%s')]".formatted(ENERGIA_ANDINA)).doesNotExist());
        consultar("/interno/v1/catalogo/empresas?codigoMunicipio=11001&tipoServicio=")
                .andExpect(jsonPath("$[?(@.idEmpresa == '%s')]".formatted(ENERGIA_ANDINA)).exists());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalogo.empresas", Integer.class)).isGreaterThan(1500);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM (SELECT id_zona, tipo_servicio FROM catalogo.coberturas_zona
                                      GROUP BY 1, 2 HAVING count(*) > 1) d""", Integer.class))
                .as("una sola empresa por zona y servicio").isZero();
    }

    @Test
    void abreElCasoConHistoriaYLoListaPorPrioridad() throws Exception {
        UUID empresa = UUID.fromString(LIME);
        UUID baja = abrirCaso("BAJA", empresa.toString());
        UUID critica = abrirCaso("CRITICA", empresa.toString());

        consultar("/interno/v1/casos/" + critica).andExpect(status().isOk())
                .andExpect(jsonPath("$.historia[0].estado").value("RECIBIDO"))
                .andExpect(jsonPath("$.nombreMunicipio").value("BOGOTÁ, D.C."))
                .andExpect(jsonPath("$.version").value(0));
        consultar("/interno/v1/casos?idEmpresa=" + empresa)
                .andExpect(jsonPath("$.elementos[0].idCaso").value(critica.toString()))
                .andExpect(jsonPath("$.elementos[1].idCaso").value(baja.toString()));

        // Orden por fecha de apertura: un caso más reciente pero menos urgente.
        UUID media = abrirCaso("MEDIA", empresa.toString());
        jdbc.update("UPDATE atencion.casos SET abierto_en = ?::timestamptz WHERE id = ?", "2026-11-17T10:00:00Z", baja);
        jdbc.update("UPDATE atencion.casos SET abierto_en = ?::timestamptz WHERE id = ?", "2026-11-17T11:00:00Z", critica);
        jdbc.update("UPDATE atencion.casos SET abierto_en = ?::timestamptz WHERE id = ?", "2026-11-17T12:00:00Z", media);
        String base = "/interno/v1/casos?idEmpresa=" + empresa;
        consultar(base).andExpect(jsonPath("$.elementos[*].idCaso").value(
                org.hamcrest.Matchers.contains(critica.toString(), media.toString(), baja.toString())));
        consultar(base + "&orden=RECIENTES").andExpect(jsonPath("$.elementos[*].idCaso").value(
                org.hamcrest.Matchers.contains(media.toString(), critica.toString(), baja.toString())));
        consultar(base + "&orden=antiguos").andExpect(jsonPath("$.elementos[*].idCaso").value(
                org.hamcrest.Matchers.contains(baja.toString(), critica.toString(), media.toString())));
        consultar(base + "&orden=ALFABETICO").andExpect(status().isBadRequest());
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
        consultar("/interno/v1/operadores/" + operador + "?idEmpresa=" + EAAB)
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
