package co.edu.javeriana.servired.persistencia.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.CatalogoTerritorial;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.Outbox;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RegistroIdempotencia;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioReportes;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.UnidadDeTrabajo;
import co.edu.javeriana.servired.persistencia.dominio.AccesoDenegado;
import co.edu.javeriana.servired.persistencia.dominio.EventoSaliente;
import co.edu.javeriana.servired.persistencia.dominio.IdCorrelacion;
import co.edu.javeriana.servired.persistencia.dominio.Municipio;
import co.edu.javeriana.servired.persistencia.dominio.OperacionNegocio;
import co.edu.javeriana.servired.persistencia.dominio.ReporteGuardado;
import co.edu.javeriana.servired.persistencia.dominio.ReporteNuevo;
import co.edu.javeriana.servired.persistencia.dominio.ServicioCliente;
import co.edu.javeriana.servired.persistencia.dominio.SolicitudRechazada;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class GuardarReporteTest {

    private final Map<UUID, ReporteNuevo> reportes = new HashMap<>();
    private final Map<String, UUID> procesadas = new HashMap<>();
    private final List<EventoSaliente> outbox = new ArrayList<>();

    private final RepositorioReportes repositorio = new RepositorioReportes() {
        public void insertar(ReporteNuevo r) {
            reportes.put(r.idReporte(), r);
        }

        public Optional<ReporteGuardado> buscar(UUID id) {
            return Optional.ofNullable(reportes.get(id)).map(r -> new ReporteGuardado(r.idReporte(),
                    r.idCiudadano(), r.tipoServicio(), r.latitud(), r.longitud(), r.codigoMunicipio(), "X",
                    r.inicioEstimado(), r.magnitud(), r.descripcion(), "RECIBIDO", r.fechaRegistro(), null, null, 0,
                    ReporteGuardado.Conteo.VACIO, ReporteGuardado.Conteo.VACIO, List.of(), List.of()));
        }
    };

    private final RegistroIdempotencia idempotencia = new RegistroIdempotencia() {
        public Optional<UUID> reclamar(IdCorrelacion c, OperacionNegocio o, ServicioCliente s, String titular) {
            return Optional.ofNullable(procesadas.get(c.valor() + o.nombre()));
        }

        public void registrarResultado(IdCorrelacion c, OperacionNegocio o, UUID id) {
            procesadas.put(c.valor() + o.nombre(), id);
        }
    };

    private final CatalogoTerritorial catalogo = new CatalogoTerritorial() {
        public Optional<Municipio> municipioMasCercano(double lat, double lon) {
            return Optional.empty();
        }

        public boolean existeMunicipio(String codigo) {
            return codigo.equals("11001");
        }
    };

    private final UnidadDeTrabajo directa = new UnidadDeTrabajo() {
        public <T> T ejecutar(Supplier<T> bloque) {
            return bloque.get();
        }
    };

    private final Outbox bandeja = outbox::add;

    private final GuardarReporte casoDeUso = new GuardarReporte(directa, idempotencia, repositorio, catalogo, bandeja);
    private final IdCorrelacion correlacion = IdCorrelacion.nuevo();

    private ReporteNuevo reporte(String municipio) {
        return new ReporteNuevo(UUID.randomUUID(), UUID.randomUUID(), "ENERGIA", 4.6, -74.1, municipio,
                Instant.parse("2026-11-14T10:00:00Z"), "CUADRA", null, Instant.parse("2026-11-14T10:05:00Z"));
    }

    private EventoSaliente evento() {
        return new EventoSaliente(UUID.randomUUID(), "reporte.creado", 1,
                UUID.fromString(correlacion.valor()), "reportes", "{}");
    }

    @Test
    void guardaElReporteYDejaElEventoEnElOutbox() {
        var r = reporte("11001");

        var resultado = casoDeUso.ejecutar(ServicioCliente.REPORTES, correlacion, r, evento());

        assertThat(resultado.nuevo()).isTrue();
        assertThat(resultado.reporte().idReporte()).isEqualTo(r.idReporte());
        assertThat(outbox).hasSize(1);
    }

    @Test
    void unReintentoDevuelveElReporteOriginalSinDuplicar() {
        var original = reporte("11001");
        casoDeUso.ejecutar(ServicioCliente.REPORTES, correlacion, original, evento());

        var reintento = casoDeUso.ejecutar(ServicioCliente.REPORTES, correlacion, reporte("11001"), evento());

        assertThat(reintento.nuevo()).isFalse();
        assertThat(reintento.reporte().idReporte()).isEqualTo(original.idReporte());
        assertThat(reportes).hasSize(1);
        assertThat(outbox).hasSize(1);
    }

    @Test
    void rechazaMunicipiosInexistentesSinEmitirEvento() {
        assertThatThrownBy(() -> casoDeUso.ejecutar(ServicioCliente.REPORTES, correlacion, reporte("99999"), evento()))
                .isInstanceOf(SolicitudRechazada.class);
        assertThat(outbox).isEmpty();
    }

    @Test
    void soloReportesPuedeGuardar() {
        assertThatThrownBy(() -> casoDeUso.ejecutar(ServicioCliente.INDICADORES, correlacion, reporte("11001"), evento()))
                .isInstanceOf(AccesoDenegado.class);
    }
}
