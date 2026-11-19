package co.edu.javeriana.servired.persistencia;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.javeriana.servired.persistencia.aplicacion.LimpiezaAlmacen;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioFase4;
import co.edu.javeriana.servired.persistencia.infraestructura.almacen.AlmacenS3;
import co.edu.javeriana.servired.persistencia.infraestructura.almacen.TareaLimpiezaAlmacen;
import co.edu.javeriana.servired.persistencia.soporte.AlmacenDePrueba;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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

/** Limpieza de huérfanos contra un almacén S3 real (SeaweedFS) y PostgreSQL. */
@SpringBootTest
@Testcontainers
class LimpiezaAlmacenIntegracionTest {

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
    AlmacenS3 s3;

    @Autowired
    RepositorioFase4 repositorio;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TareaLimpiezaAlmacen tarea;

    @Autowired
    MeterRegistry metricas;

    @BeforeEach
    void bucketVacio() {
        for (String prefijo : List.of("reportes/", "denuncias/", "otros/")) {
            var claves = new ArrayList<String>();
            s3.recorrer(prefijo, p -> p.forEach(o -> claves.add(o.clave())));
            claves.forEach(s3::eliminar);
        }
    }

    /** Una foto con su evidencia en la base, como la deja GestionEvidencias. */
    private String fotoReferenciada() {
        UUID reporte = UUID.randomUUID();
        UUID evidencia = UUID.randomUUID();
        String clave = "reportes/" + reporte + "/" + evidencia + ".jpg";
        jdbc.update("""
                INSERT INTO reportes.reportes (id, id_ciudadano, tipo_servicio, latitud, longitud, codigo_municipio,
                    inicio_estimado, magnitud, estado, fecha_registro)
                VALUES (?, ?, 'ENERGIA', 4.6, -74.08, '11001', now() - interval '2 hours', 'BARRIO', 'RECIBIDO', now())""",
                reporte, UUID.randomUUID());
        jdbc.update("""
                INSERT INTO reportes.evidencias (id, id_reporte, clave_objeto, tipo_contenido, tamano_bytes, sha256, creada_en)
                VALUES (?, ?, ?, 'image/jpeg', 3, ?, now())""", evidencia, reporte, clave, "0".repeat(64));
        s3.guardar(clave, "image/jpeg", new byte[] {1, 2, 3});
        return clave;
    }

    private String huerfana(String prefijo) {
        String clave = prefijo + UUID.randomUUID() + "/" + UUID.randomUUID() + ".jpg";
        s3.guardar(clave, "image/jpeg", new byte[] {4, 5, 6});
        return clave;
    }

    /** Como si hubiera pasado el período de gracia desde que se subieron. */
    private LimpiezaAlmacen dentroDeDosHoras() {
        return new LimpiezaAlmacen(s3, repositorio, Clock.offset(Clock.systemUTC(), Duration.ofHours(2)),
                Duration.ofHours(1), 5, 0.2);
    }

    @Test
    void borraLasHuerfanasYConservaLasReferenciadasYLasAjenas() {
        var referenciadas = new ArrayList<String>();
        for (int i = 0; i < 12; i++) {
            referenciadas.add(fotoReferenciada());
        }
        String deReporte = huerfana("reportes/");
        String deDenuncia = huerfana("denuncias/");
        String ajena = huerfana("otros/");

        var r = dentroDeDosHoras().ejecutar();

        assertThat(r.revisados()).isEqualTo(14);
        assertThat(r.borrados()).isEqualTo(2);
        assertThat(s3.leer(deReporte)).isEmpty();
        assertThat(s3.leer(deDenuncia)).isEmpty();
        assertThat(s3.leer(ajena)).isPresent();
        referenciadas.forEach(c -> assertThat(s3.leer(c)).as(c).isPresent());
    }

    @Test
    void conBaseVaciaJuntoAUnBucketConFotosNoBorraNada() {
        var claves = new ArrayList<String>();
        for (int i = 0; i < 8; i++) {
            claves.add(huerfana("reportes/"));
        }

        var r = dentroDeDosHoras().ejecutar();

        assertThat(r.frenada()).isTrue();
        claves.forEach(c -> assertThat(s3.leer(c)).as(c).isPresent());
    }

    @Test
    void laTareaProgramadaRespetaElPeriodoDeGraciaYRegistraMetricas() {
        String recien = huerfana("reportes/");   // recién subida: su transacción podría estar en curso

        tarea.ejecutar();

        assertThat(s3.leer(recien)).isPresent();
        assertThat(metricas.find("servired.almacen.huerfanos.borrados").counter()).isNotNull();
        assertThat(metricas.find("servired.almacen.limpieza.fallas").counter().count()).isZero();
    }
}
