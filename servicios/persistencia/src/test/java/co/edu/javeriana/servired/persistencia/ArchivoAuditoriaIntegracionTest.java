package co.edu.javeriana.servired.persistencia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.javeriana.servired.persistencia.aplicacion.ArchivoAuditoria;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.CodificadorSegmentos;
import co.edu.javeriana.servired.persistencia.dominio.ServicioCliente;
import co.edu.javeriana.servired.persistencia.infraestructura.almacen.AlmacenS3;
import co.edu.javeriana.servired.persistencia.soporte.AlmacenDePrueba;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
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

/**
 * Archivado del registro de auditoría conservando la cadena de huellas (ADR-29), contra
 * PostgreSQL y un almacén S3 reales. Los pasos dependen del anterior: van en orden.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {"servired.claves.auditoria=clave-auditoria-prueba",
        "servired.auditoria.archivo.activo=false"})
class ArchivoAuditoriaIntegracionTest {

    static final String AUDITORIA = "clave-auditoria-prueba";

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

    @Autowired
    ArchivoAuditoria archivo;

    @Autowired
    AlmacenS3 s3;

    @Autowired
    CodificadorSegmentos codificador;

    private void registrar(int n) throws Exception {
        for (int i = 0; i < n; i++) {
            mvc.perform(post("/interno/v1/auditoria/registros").header("X-Clave-Servicio", AUDITORIA)
                            .header("X-Correlation-Id", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON).content("""
                                    {"idEvento":"%s","tipo":"accion.auditada","version":1,"origen":"identidad",
                                     "categoria":"ACCESO","accion":"INICIO_SESION","resultado":"EXITO",
                                     "actorId":"%s","actorRol":"CIUDADANO",
                                     "detalle":{"intento":%d,"anidado":{"b":2,"a":"ñandú"}},
                                     "ocurridoEn":"2026-11-17T10:00:00.123456Z"}"""
                                    .formatted(UUID.randomUUID(), UUID.randomUUID(), i)))
                    .andExpect(status().isCreated());
        }
    }

    private ResultActions integridad() throws Exception {
        return mvc.perform(get("/interno/v1/auditoria/integridad").header("X-Clave-Servicio", AUDITORIA)
                .header("X-Correlation-Id", UUID.randomUUID().toString()));
    }

    private long enBase() {
        return jdbc.queryForObject("SELECT count(*) FROM auditoria.registros", Long.class);
    }

    @Test
    @Order(1)
    void archivaLoRegistradoYLaCadenaSigueVerificable() throws Exception {
        registrar(7);
        String ultimoAntes = jdbc.queryForObject("SELECT hash FROM auditoria.registros ORDER BY secuencia DESC LIMIT 1",
                String.class);

        var r = archivo.archivar(Instant.now().plusSeconds(1));

        assertThat(r.segmentosNuevos()).isEqualTo(1);
        assertThat(r.registrosArchivados()).isEqualTo(7);
        assertThat(r.detenidoPor()).isNull();
        assertThat(enBase()).isZero();
        integridad().andExpect(status().isOk())
                .andExpect(jsonPath("$.integra").value(true))
                .andExpect(jsonPath("$.registros").value(7))
                .andExpect(jsonPath("$.registrosEnBase").value(0))
                .andExpect(jsonPath("$.registrosArchivados").value(7))
                .andExpect(jsonPath("$.segmentos").value(1))
                .andExpect(jsonPath("$.ultimoHash").value(ultimoAntes));

        // Lo que llega después enlaza con el último registro archivado.
        registrar(3);
        assertThat(jdbc.queryForObject("SELECT hash_anterior FROM auditoria.registros ORDER BY secuencia LIMIT 1",
                String.class)).isEqualTo(ultimoAntes);
        archivo.archivar(Instant.now().plusSeconds(1));
        registrar(2);
        integridad().andExpect(jsonPath("$.integra").value(true))
                .andExpect(jsonPath("$.registros").value(12))
                .andExpect(jsonPath("$.registrosEnBase").value(2))
                .andExpect(jsonPath("$.segmentos").value(2));
    }

    @Test
    @Order(2)
    void laVerificacionProfundaDescargaCadaSegmentoYRecalculaSusHuellas() throws Exception {
        mvc.perform(post("/interno/v1/auditoria/archivo/verificacion").header("X-Clave-Servicio", AUDITORIA)
                        .header("X-Correlation-Id", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.integro").value(true))
                .andExpect(jsonPath("$.segmentos").value(2))
                .andExpect(jsonPath("$.registros").value(10));
        mvc.perform(get("/interno/v1/auditoria/segmentos").header("X-Clave-Servicio", AUDITORIA)
                        .header("X-Correlation-Id", UUID.randomUUID().toString()))
                .andExpect(jsonPath("$[0].registros").value(7))
                .andExpect(jsonPath("$[1].hashAnteriorInicial").value(
                        jdbc.queryForObject("SELECT hash_final FROM auditoria.segmentos ORDER BY id LIMIT 1", String.class)));
        // El archivo es legible sin SERVI-RED: JSONL con el detalle canónico.
        String clave = jdbc.queryForObject("SELECT clave_objeto FROM auditoria.segmentos ORDER BY id LIMIT 1", String.class);
        var filas = codificador.decodificar(s3.leer(clave).orElseThrow());
        assertThat(filas).hasSize(7);
        assertThat(filas.getFirst().detalle()).contains("ñandú");
        assertThat(filas.getFirst().ocurridoEn()).isEqualTo("2026-11-17T10:00:00.123456Z");
    }

    @Test
    @Order(3)
    void soloSeBorraLoQueYaCubreUnSegmentoYNadaSeModifica() {
        long vivo = jdbc.queryForObject("SELECT min(secuencia) FROM auditoria.registros", Long.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM auditoria.registros WHERE secuencia = ?", vivo))
                .hasMessageContaining("solo inserción");
        // Ni con la marca de archivado, si ningún segmento lo cubre.
        assertThatThrownBy(() -> jdbc.execute((ConnectionCallback<Object>) c -> {
            c.setAutoCommit(false);
            try (var s = c.createStatement()) {
                s.execute("SELECT set_config('servired.archivando_auditoria', 'si', true)");
                s.execute("DELETE FROM auditoria.registros WHERE secuencia = " + vivo);
            } finally {
                c.rollback();
                c.setAutoCommit(true);
            }
            return null;
        })).hasMessageContaining("solo inserción");
        assertThatThrownBy(() -> jdbc.update("UPDATE auditoria.segmentos SET hash_final = repeat('0', 64)"))
                .hasMessageContaining("solo inserción");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM auditoria.segmentos")).hasMessageContaining("solo inserción");
    }

    @Test
    @Order(4)
    void unArchivoAlteradoEnElAlmacenSeDetecta() throws Exception {
        String clave = jdbc.queryForObject("SELECT clave_objeto FROM auditoria.segmentos ORDER BY id LIMIT 1", String.class);
        byte[] original = s3.leer(clave).orElseThrow();
        var filas = new java.util.ArrayList<>(codificador.decodificar(original));
        var r = filas.get(3);
        filas.set(3, new co.edu.javeriana.servired.persistencia.dominio.Auditoria.RegistroArchivado(r.secuencia(),
                r.idEvento(), r.tipo(), r.version(), r.origen(), r.correlationId(), r.categoria(), "OTRA_ACCION",
                r.resultado(), r.actorId(), r.actorRol(), r.recursoTipo(), r.recursoId(), r.detalle(), r.ocurridoEn(),
                r.registradoEn(), r.hashAnterior(), r.hash()));
        s3.guardar(clave, "application/gzip", codificador.codificar(filas));
        try {
            var v = archivo.verificar(ServicioCliente.AUDITORIA);
            assertThat(v.integro()).isFalse();
            assertThat(v.problema()).contains("SHA-256");
        } finally {
            s3.guardar(clave, "application/gzip", original);
        }
        assertThat(archivo.verificar(ServicioCliente.AUDITORIA).integro()).isTrue();
    }

    @Test
    @Order(5)
    void noArchivaUnTramoAlteradoEnLaBase() throws Exception {
        registrar(2);
        // Alguien con acceso a la base desactiva los triggers y altera un registro.
        jdbc.execute((ConnectionCallback<Object>) c -> {
            try (var s = c.createStatement()) {
                s.execute("SET session_replication_role = replica");
                s.execute("UPDATE auditoria.registros SET accion = 'MANIPULADA' "
                        + "WHERE secuencia = (SELECT min(secuencia) FROM auditoria.registros)");
                s.execute("SET session_replication_role = DEFAULT");
            }
            return null;
        });
        long antes = enBase();

        var r = archivo.archivar(Instant.now().plusSeconds(1));

        assertThat(r.segmentosNuevos()).isZero();
        assertThat(r.detenidoPor()).contains("rota");
        assertThat(enBase()).as("el tramo alterado se queda en la base como evidencia").isEqualTo(antes);
        integridad().andExpect(jsonPath("$.integra").value(false)).andExpect(jsonPath("$.primeraRota").isNumber());
    }
}
