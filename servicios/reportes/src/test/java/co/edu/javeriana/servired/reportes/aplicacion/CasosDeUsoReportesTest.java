package co.edu.javeriana.servired.reportes.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.javeriana.servired.reportes.dominio.IdCorrelacion;
import co.edu.javeriana.servired.reportes.dominio.Magnitud;
import co.edu.javeriana.servired.reportes.dominio.ReporteInvalido;
import co.edu.javeriana.servired.reportes.dominio.ReporteNoEncontrado;
import co.edu.javeriana.servired.reportes.dominio.TipoServicio;
import co.edu.javeriana.servired.reportes.dominio.Ubicacion;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CasosDeUsoReportesTest {

    private final PersistenciaEnMemoria persistencia = new PersistenciaEnMemoria();
    private final Clock reloj = Clock.fixed(Instant.parse("2026-11-14T15:00:00Z"), ZoneOffset.UTC);
    private final CrearReporte crear = new CrearReporte(persistencia, persistencia, reloj);
    private final ConsultarReporte consultar = new ConsultarReporte(persistencia);
    private final UUID ciudadano = UUID.randomUUID();
    private final IdCorrelacion correlacion = IdCorrelacion.nuevo();

    private CrearReporte.Comando comando() {
        return new CrearReporte.Comando(TipoServicio.ACUEDUCTO, new Ubicacion(4.6287, -74.0648),
                Instant.parse("2026-11-14T13:00:00Z"), Magnitud.BARRIO, "Sin agua");
    }

    @Test
    void registraConElMunicipioResueltoYEmiteReporteCreado() {
        var reporte = crear.ejecutar(ciudadano, comando(), correlacion);

        assertThat(reporte.municipio().codigo()).isEqualTo("11001");
        var evento = persistencia.ultimoEvento;
        assertThat(evento.tipo()).isEqualTo("reporte.creado");
        assertThat(evento.version()).isEqualTo(1);
        assertThat(evento.origen()).isEqualTo("reportes");
        assertThat(evento.correlationId().toString()).isEqualTo(correlacion.valor());
        assertThat(evento.datos()).containsEntry("idReporte", reporte.idReporte().toString())
                .containsEntry("codigoMunicipio", "11001")
                .containsEntry("tipoServicio", "ACUEDUCTO");
    }

    @Test
    void elEventoNoContieneDatosDelCiudadano() {
        crear.ejecutar(ciudadano, comando(), correlacion);

        assertThat(persistencia.ultimoEvento.datos().toString()).doesNotContain(ciudadano.toString());
    }

    @Test
    void unReporteInvalidoNoLlegaAPersistencia() {
        var invalido = new CrearReporte.Comando(TipoServicio.GAS, new Ubicacion(0, 0), null, Magnitud.CUADRA, null);

        assertThatThrownBy(() -> crear.ejecutar(ciudadano, invalido, correlacion)).isInstanceOf(ReporteInvalido.class);
        assertThat(persistencia.ultimoEvento).isNull();
    }

    @Test
    void elCiudadanoConsultaSuReporte() {
        var creado = crear.ejecutar(ciudadano, comando(), correlacion);

        assertThat(consultar.ejecutar(ciudadano, creado.idReporte(), correlacion).estado().name())
                .isEqualTo("RECIBIDO");
    }

    @Test
    void otroCiudadanoNoPuedeVerlo() {
        var creado = crear.ejecutar(ciudadano, comando(), correlacion);

        assertThatThrownBy(() -> consultar.ejecutar(UUID.randomUUID(), creado.idReporte(), correlacion))
                .isInstanceOf(ReporteNoEncontrado.class);
    }
}
