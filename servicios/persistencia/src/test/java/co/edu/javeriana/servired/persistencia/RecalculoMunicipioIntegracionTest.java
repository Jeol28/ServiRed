package co.edu.javeriana.servired.persistencia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.javeriana.servired.persistencia.soporte.AlmacenDePrueba;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Recálculo del municipio de los reportes ubicados por centroide (ADR-32): la función que llama la
 * migración V17, sobre reportes sembrados con el municipio que les daba el centroide.
 */
@SpringBootTest
@Testcontainers
class RecalculoMunicipioIntegracionTest {

    static final String ENERGIA_ANDINA = "a0000000-0000-4000-8000-000000000001";   // cubre ENERGIA en Bogotá
    static final String AGUAS = "a0000000-0000-4000-8000-000000000002";

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
    JdbcTemplate jdbc;

    private UUID reporte(double lat, double lon, String municipioGuardado) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO reportes.reportes (id, id_ciudadano, tipo_servicio, latitud, longitud, codigo_municipio,
                    inicio_estimado, magnitud, estado, fecha_registro)
                VALUES (?, ?, 'ENERGIA', ?, ?, ?, '2026-11-10T08:00:00Z', 'CUADRA', 'RECIBIDO', '2026-11-10T09:00:00Z')""",
                id, UUID.randomUUID(), lat, lon, municipioGuardado);
        jdbc.update("""
                INSERT INTO indicadores.vista_reportes (id_reporte, tipo_servicio, codigo_municipio, latitud, longitud,
                    fecha_registro, id_empresa)
                VALUES (?, 'ENERGIA', ?, ?, ?, '2026-11-10T09:00:00Z', NULL)""", id, municipioGuardado, lat, lon);
        return id;
    }

    private UUID caso(UUID reporte, String municipio, String estado, String empresa, UUID asignadoA) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO atencion.casos (id, id_reporte, tipo_servicio, codigo_municipio, magnitud, estado, abierto_en,
                    id_empresa, prioridad, asignado_a)
                VALUES (?, ?, 'ENERGIA', ?, 'CUADRA', ?, '2026-11-10T09:00:01Z', ?::uuid, 'MEDIA', ?)""",
                id, reporte, municipio, estado, empresa, asignadoA);
        jdbc.update("UPDATE indicadores.vista_reportes SET id_empresa = ?::uuid WHERE id_reporte = ?", empresa, reporte);
        return id;
    }

    private Map<String, Object> casoActual(UUID caso) {
        return jdbc.queryForMap("SELECT codigo_municipio, id_empresa::text AS empresa, version FROM atencion.casos "
                + "WHERE id = ?", caso);
    }

    private String municipio(UUID reporte) {
        return jdbc.queryForObject("SELECT codigo_municipio FROM reportes.reportes WHERE id = ?", String.class, reporte);
    }

    @Test
    void corrigeElMunicipioReenrutaSoloLosCasosIntactosYDejaRegistro() {
        // Bosa (Bogotá) guardado en Soacha: caso RECIBIDO, sin asignar y con el enrutamiento automático
        // de Soacha (ninguna empresa cubre ENERGIA allá) → se reenruta a la empresa de Bogotá.
        UUID intacto = reporte(4.60334, -74.20528, "25754");
        UUID casoIntacto = caso(intacto, "25754", "RECIBIDO", null, null);
        // Norte de Bogotá guardado en Chía, ya EN_ATENCION: cambia el municipio, no la empresa.
        UUID enAtencion = reporte(4.82219, -74.03851, "25175");
        UUID casoEnAtencion = caso(enAtencion, "25175", "EN_ATENCION", AGUAS, UUID.randomUUID());
        // RECIBIDO y sin asignar, pero enrutado A MANO (no es el automático): no se toca la empresa.
        UUID manual = reporte(4.60334, -74.20530, "25754");
        UUID casoManual = caso(manual, "25754", "RECIBIDO", AGUAS, null);
        // Soacha bien ubicado: no cambia nada.
        UUID correcto = reporte(4.5793, -74.2168, "25754");
        // Sin caso todavía.
        UUID sinCaso = reporte(4.60334, -74.20540, "25754");
        long versionAntes = (int) casoActual(casoEnAtencion).get("version");

        var r = jdbc.queryForMap("SELECT * FROM reportes.recalcular_municipios('PRUEBA')");

        assertThat(r).containsEntry("reportes_cambiados", 4L).containsEntry("casos_cambiados", 3L)
                .containsEntry("casos_reenrutados", 1L);
        assertThat(municipio(intacto)).isEqualTo("11001");
        assertThat(municipio(enAtencion)).isEqualTo("11001");
        assertThat(municipio(correcto)).isEqualTo("25754");
        assertThat(municipio(sinCaso)).isEqualTo("11001");

        assertThat(casoActual(casoIntacto)).containsEntry("codigo_municipio", "11001")
                .containsEntry("empresa", ENERGIA_ANDINA);
        assertThat(casoActual(casoEnAtencion)).containsEntry("codigo_municipio", "11001").containsEntry("empresa", AGUAS)
                .containsEntry("version", (int) versionAntes + 1);
        assertThat(casoActual(casoManual)).containsEntry("empresa", AGUAS);

        assertThat(jdbc.queryForObject("SELECT detalle FROM atencion.historia_caso WHERE id_caso = ? ORDER BY id DESC "
                + "LIMIT 1", String.class, casoIntacto))
                .contains("Municipio corregido de 25754 a 11001").contains("reenrutado");
        assertThat(jdbc.queryForObject("SELECT codigo_municipio || '|' || id_empresa FROM indicadores.vista_reportes "
                + "WHERE id_reporte = ?", String.class, intacto)).isEqualTo("11001|" + ENERGIA_ANDINA);

        // El registro de qué cambió.
        var registro = jdbc.queryForMap("SELECT municipio_anterior, municipio_nuevo, empresa_anterior::text AS antes, "
                + "empresa_nueva::text AS despues, caso_reenrutado, migracion FROM reportes.recalculo_municipio "
                + "WHERE id_reporte = ?", intacto);
        assertThat(registro).containsEntry("municipio_anterior", "25754").containsEntry("municipio_nuevo", "11001")
                .containsEntry("despues", ENERGIA_ANDINA).containsEntry("caso_reenrutado", true)
                .containsEntry("migracion", "PRUEBA");
        assertThat(registro.get("antes")).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reportes.recalculo_municipio WHERE migracion = 'PRUEBA'",
                Integer.class)).isEqualTo(4);
        assertThatThrownBy(() -> jdbc.update("UPDATE reportes.recalculo_municipio SET municipio_nuevo = '05001'"))
                .hasMessageContaining("solo inserción");

        // Volver a correrla no cambia nada más.
        assertThat(jdbc.queryForMap("SELECT * FROM reportes.recalcular_municipios('PRUEBA-2')"))
                .containsEntry("reportes_cambiados", 0L);
    }
}
