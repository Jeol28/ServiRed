package co.edu.javeriana.servired.reportes.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EstadoPorCasoTest {

    @Test
    void mapeaLosEstadosDelCasoALosDelCiudadano() {
        assertThat(EstadoPorCaso.para("caso.actualizado", "EN_ATENCION", null)).get()
                .extracting(EstadoPorCaso::estadoNuevo).isEqualTo(EstadoReporte.EN_ATENCION);
        assertThat(EstadoPorCaso.para("caso.actualizado", "RESUELTO", null)).get()
                .extracting(EstadoPorCaso::estadoNuevo).isEqualTo(EstadoReporte.RESTABLECIDO);
        assertThat(EstadoPorCaso.para("caso.cerrado", null, "Transformador reemplazado")).get()
                .satisfies(c -> {
                    assertThat(c.estadoNuevo()).isEqualTo(EstadoReporte.CERRADO);
                    assertThat(c.detalle()).contains("Transformador reemplazado");
                });
    }

    @Test
    void ignoraCambiosQueNoAfectanAlCiudadano() {
        assertThat(EstadoPorCaso.para("caso.actualizado", "RECIBIDO", null)).isEmpty();
        assertThat(EstadoPorCaso.para("reporte.creado", "EN_ATENCION", null)).isEmpty();
    }

    @Test
    void nuncaRetrocede() {
        var enAtencion = EstadoPorCaso.para("caso.actualizado", "EN_ATENCION", null).orElseThrow();
        assertThat(enAtencion.desde()).containsExactly(EstadoReporte.RECIBIDO);
        assertThat(EstadoPorCaso.para("caso.cerrado", null, null).orElseThrow().desde())
                .doesNotContain(EstadoReporte.CERRADO);
    }

    @Test
    void siElOperadorResolvioPrimeroElCiudadanoAunRegistraLaHora() {
        UUID autor = UUID.randomUUID();
        Instant inicio = Instant.parse("2026-11-17T08:00:00Z");
        var resuelto = new Reporte(UUID.randomUUID(), autor, TipoServicio.ENERGIA, new Ubicacion(4.6, -74.1),
                new Municipio("11001", "BOGOTÁ"), inicio, Magnitud.CUADRA, null, EstadoReporte.RESTABLECIDO, inicio,
                null, null, 0, null, null, List.of(), List.of());

        assertThat(resuelto.restablecer(autor, Instant.parse("2026-11-17T09:30:00Z"), null,
                Instant.parse("2026-11-17T10:00:00Z")).duracionMinutos()).isEqualTo(90);
    }
}
