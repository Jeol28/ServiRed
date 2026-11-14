package co.edu.javeriana.servired.persistencia.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventoSalienteTest {

    private final UUID correlacion = UUID.randomUUID();

    private EventoSaliente evento(String tipo, String origen) {
        return new EventoSaliente(UUID.randomUUID(), tipo, 1, correlacion, origen, "{}");
    }

    @Test
    void laRoutingKeyIncluyeLaVersion() {
        assertThat(evento("reporte.creado", "reportes").routingKey()).isEqualTo("reporte.creado.v1");
    }

    @Test
    void rechazaTiposDesconocidos() {
        assertThatThrownBy(() -> evento("reporte.borrado", "reportes"))
                .isInstanceOf(SolicitudRechazada.class);
    }

    @Test
    void soloElServicioDuenoPuedeEmitirlo() {
        var e = evento("reporte.creado", "atencion");

        assertThatThrownBy(() -> e.validarPara(ServicioCliente.ATENCION,
                new IdCorrelacion(correlacion.toString()), Set.of("reporte.creado")))
                .isInstanceOf(AccesoDenegado.class);
    }

    @Test
    void elCorrelationIdDebeCoincidirConElDeLaSolicitud() {
        var e = evento("reporte.creado", "reportes");

        assertThatThrownBy(() -> e.validarPara(ServicioCliente.REPORTES, IdCorrelacion.nuevo(),
                Set.of("reporte.creado")))
                .isInstanceOf(SolicitudRechazada.class);
    }

    @Test
    void laOperacionSoloAdmiteSusEventos() {
        var e = evento("denuncia.recibida", "reportes");

        assertThatThrownBy(() -> e.validarPara(ServicioCliente.REPORTES,
                new IdCorrelacion(correlacion.toString()), Set.of("reporte.creado")))
                .isInstanceOf(SolicitudRechazada.class);
    }

    @Test
    void aceptaUnEventoValido() {
        evento("reporte.creado", "reportes").validarPara(ServicioCliente.REPORTES,
                new IdCorrelacion(correlacion.toString()), Set.of("reporte.creado"));
    }
}
