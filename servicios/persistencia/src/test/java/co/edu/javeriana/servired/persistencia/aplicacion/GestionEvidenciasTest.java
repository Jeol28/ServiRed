package co.edu.javeriana.servired.persistencia.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.AlmacenEvidencias;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.CatalogoTerritorial;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.Outbox;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RegistroIdempotencia;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioFase4;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioReportes;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.UnidadDeTrabajo;
import co.edu.javeriana.servired.persistencia.dominio.Conflicto;
import co.edu.javeriana.servired.persistencia.dominio.EventoSaliente;
import co.edu.javeriana.servired.persistencia.dominio.Fase4.ArchivoNuevo;
import co.edu.javeriana.servired.persistencia.dominio.Fase4.DenunciaGuardada;
import co.edu.javeriana.servired.persistencia.dominio.Fase4.DenunciaNueva;
import co.edu.javeriana.servired.persistencia.dominio.IdCorrelacion;
import co.edu.javeriana.servired.persistencia.dominio.RecursoNoEncontrado;
import co.edu.javeriana.servired.persistencia.dominio.ReporteGuardado;
import co.edu.javeriana.servired.persistencia.dominio.ServicioCliente;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * La foto se sube sin transacción abierta (ninguna conexión del pool queda tomada
 * durante la subida) y la transacción solo guarda la referencia; si algo falla, el
 * objeto se borra, salvo que una evidencia guardada lo referencie.
 */
class GestionEvidenciasTest {

    /** Marca cuándo hay una transacción abierta. */
    private static final class TransaccionFalsa implements UnidadDeTrabajo {
        boolean abierta;

        @Override
        public <T> T ejecutar(Supplier<T> bloque) {
            abierta = true;
            try {
                return bloque.get();
            } finally {
                abierta = false;
            }
        }
    }

    /** Registra cada operación y si ocurrió con la transacción abierta. */
    private static final class AlmacenFalso implements AlmacenEvidencias {
        final TransaccionFalsa transaccion;
        final List<String> operaciones = new ArrayList<>();

        AlmacenFalso(TransaccionFalsa transaccion) {
            this.transaccion = transaccion;
        }

        @Override
        public void guardar(String clave, String tipoContenido, byte[] contenido) {
            operaciones.add("guardar" + (transaccion.abierta ? " EN TRANSACCIÓN" : ""));
        }

        @Override
        public Optional<byte[]> leer(String clave) {
            return Optional.empty();
        }

        @Override
        public void borrar(String clave) {
            operaciones.add("borrar" + (transaccion.abierta ? " EN TRANSACCIÓN" : ""));
        }
    }

    private final TransaccionFalsa transaccion = new TransaccionFalsa();
    private final AlmacenFalso almacen = new AlmacenFalso(transaccion);
    private final RegistroIdempotencia idempotencia = mock(RegistroIdempotencia.class);
    private final RepositorioReportes reportes = mock(RepositorioReportes.class);
    private final RepositorioFase4 repositorio = mock(RepositorioFase4.class);
    private final CatalogoTerritorial catalogo = mock(CatalogoTerritorial.class);
    private final Outbox outbox = mock(Outbox.class);
    private final GestionEvidencias gestion = new GestionEvidencias(transaccion, idempotencia, reportes, repositorio,
            catalogo, almacen, outbox, Clock.fixed(Instant.parse("2026-11-18T10:00:00Z"), ZoneOffset.UTC));

    private final UUID idReporte = UUID.randomUUID();
    private final IdCorrelacion correlacion = IdCorrelacion.nuevo();
    private final ArchivoNuevo foto = new ArchivoNuevo(UUID.randomUUID(), "image/png", new byte[] {1, 2, 3});
    private final List<String> enTransaccion = new ArrayList<>();

    @BeforeEach
    void reporteExistente() {
        when(repositorio.existeReporte(idReporte)).thenReturn(true);
        when(repositorio.bloquearReporte(idReporte)).thenAnswer(i -> {
            enTransaccion.add("bloquear:" + transaccion.abierta);
            return true;
        });
        when(reportes.buscar(idReporte)).thenReturn(Optional.of(mock(ReporteGuardado.class)));
        when(idempotencia.reclamar(any(), any(), any(), any())).thenReturn(Optional.empty());
    }

    private void guardarFoto() {
        gestion.guardarDeReporte(ServicioCliente.REPORTES, correlacion, idReporte, foto);
    }

    @Test
    void subeLaFotoSinTransaccionYLuegoGuardaSoloLaReferencia() {
        when(repositorio.existeClaveEvidencia(anyString())).thenReturn(true);

        guardarFoto();

        assertThat(almacen.operaciones).containsExactly("guardar");
        assertThat(enTransaccion).containsExactly("bloquear:true");
        verify(repositorio).insertarEvidencia(any(), any(), any(), anyString(), anyString(), anyInt(), anyString(),
                any());
    }

    @Test
    void siLaTransaccionFallaBorraLaFotoFueraDeEllaYPropagaElError() {
        when(repositorio.contarEvidencias(idReporte)).thenReturn(GestionEvidencias.MAXIMO_POR_REPORTE);

        assertThatThrownBy(this::guardarFoto).isInstanceOf(Conflicto.class);

        assertThat(almacen.operaciones).containsExactly("guardar", "borrar");
    }

    @Test
    void unReporteInexistenteNoSubeNada() {
        UUID otro = UUID.randomUUID();

        assertThatThrownBy(() -> gestion.guardarDeReporte(ServicioCliente.REPORTES, correlacion, otro, foto))
                .isInstanceOf(RecursoNoEncontrado.class);

        assertThat(almacen.operaciones).isEmpty();
    }

    @Test
    void unReintentoIdempotenteNoBorraLaFotoQueYaQuedoGuardada() {
        when(idempotencia.reclamar(any(), any(), any(), any())).thenReturn(Optional.of(idReporte));
        when(repositorio.existeClaveEvidencia(anyString())).thenReturn(true);

        guardarFoto();

        assertThat(almacen.operaciones).containsExactly("guardar");
        verify(repositorio, never()).insertarEvidencia(any(), any(), any(), anyString(), anyString(), anyInt(),
                anyString(), any());
    }

    @Test
    void unReintentoIdempotenteConOtraFotoBorraLaQueNoSeUso() {
        when(idempotencia.reclamar(any(), any(), any(), any())).thenReturn(Optional.of(idReporte));
        when(repositorio.existeClaveEvidencia(anyString())).thenReturn(false);

        guardarFoto();

        assertThat(almacen.operaciones).containsExactly("guardar", "borrar");
    }

    @Test
    void laDenunciaConFotoTambienLaSubeSinTransaccion() {
        var d = new DenunciaNueva(UUID.randomUUID(), "GAS", 4.6, -74.1, "11001", "Conexión ilegal de gas",
                Instant.parse("2026-11-18T10:00:00Z"));
        var evento = new EventoSaliente(UUID.randomUUID(), "denuncia.recibida", 1, UUID.fromString(correlacion.valor()),
                "reportes", "{}");
        when(catalogo.existeMunicipio("11001")).thenReturn(true);
        when(repositorio.buscarDenuncia(d.idDenuncia())).thenReturn(Optional.of(mock(DenunciaGuardada.class)));
        when(repositorio.existeClaveEvidencia(anyString())).thenReturn(true);

        gestion.guardarDenuncia(ServicioCliente.REPORTES, correlacion, d, foto, evento);
        assertThat(almacen.operaciones).containsExactly("guardar");

        almacen.operaciones.clear();
        var sinFoto = new DenunciaNueva(UUID.randomUUID(), "GAS", 4.6, -74.1, "11001", "Otra conexión ilegal",
                Instant.parse("2026-11-18T10:00:00Z"));
        when(repositorio.buscarDenuncia(sinFoto.idDenuncia())).thenReturn(Optional.of(mock(DenunciaGuardada.class)));
        gestion.guardarDenuncia(ServicioCliente.REPORTES, correlacion, sinFoto, null, evento);
        assertThat(almacen.operaciones).as("sin foto no toca el almacén").isEmpty();
    }
}
