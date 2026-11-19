package co.edu.javeriana.servired.persistencia.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.AlmacenEvidencias;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioFase4;
import co.edu.javeriana.servired.persistencia.dominio.ObjetoAlmacenado;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Qué borra la limpieza de huérfanos y, sobre todo, qué no borra. */
class LimpiezaAlmacenTest {

    private static final Instant AHORA = Instant.parse("2026-11-18T12:00:00Z");
    private static final Instant VIEJO = AHORA.minus(Duration.ofHours(3));
    private static final Instant RECIENTE = AHORA.minus(Duration.ofMinutes(10));

    /** Almacén en memoria con páginas de 2 objetos, para ejercitar la paginación. */
    private static final class AlmacenMemoria implements AlmacenEvidencias {
        final Map<String, Instant> objetos = new TreeMap<>();
        final Set<String> quebradizos = new HashSet<>();
        Runnable alListar = () -> { };

        @Override
        public void guardar(String clave, String tipoContenido, byte[] contenido) {
            objetos.put(clave, AHORA);
        }

        @Override
        public Optional<byte[]> leer(String clave) {
            return Optional.empty();
        }

        @Override
        public void borrar(String clave) {
            objetos.remove(clave);
        }

        @Override
        public void eliminar(String clave) {
            if (quebradizos.contains(clave)) {
                throw new IllegalStateException("almacén sin responder");
            }
            objetos.remove(clave);
        }

        @Override
        public void recorrer(String prefijo, Consumer<List<ObjetoAlmacenado>> porPagina) {
            var lista = objetos.entrySet().stream().filter(e -> e.getKey().startsWith(prefijo))
                    .map(e -> new ObjetoAlmacenado(e.getKey(), e.getValue())).toList();
            for (int i = 0; i < lista.size(); i += 2) {
                porPagina.accept(lista.subList(i, Math.min(i + 2, lista.size())));
            }
            alListar.run();
        }
    }

    /** La base: qué claves están referenciadas por una evidencia. */
    private static final class Referencias {
        final Set<String> claves = new HashSet<>();

        RepositorioFase4 repositorio() {
            var r = Mockito.mock(RepositorioFase4.class);
            Mockito.when(r.clavesReferenciadas(Mockito.any())).thenAnswer(i -> {
                Collection<String> pedidas = i.getArgument(0);
                return pedidas.stream().filter(claves::contains).collect(Collectors.toSet());
            });
            Mockito.when(r.existeClaveEvidencia(Mockito.anyString())).thenAnswer(i -> claves.contains(i.<String>getArgument(0)));
            return r;
        }
    }

    private final AlmacenMemoria almacen = new AlmacenMemoria();
    private final Referencias base = new Referencias();

    private LimpiezaAlmacen limpieza() {
        return new LimpiezaAlmacen(almacen, base.repositorio(), Clock.fixed(AHORA, ZoneOffset.UTC), Duration.ofHours(1),
                5, 0.2);
    }

    private void objeto(String clave, Instant cuando, boolean referenciado) {
        almacen.objetos.put(clave, cuando);
        if (referenciado) {
            base.claves.add(clave);
        }
    }

    @Test
    void borraSoloLosViejosSinReferenciaDeLosPrefijosDePersistencia() {
        for (int i = 0; i < 20; i++) {
            objeto("reportes/r" + i + "/foto.jpg", VIEJO, true);
        }
        objeto("reportes/r9/huerfana.jpg", VIEJO, false);
        objeto("denuncias/d1/huerfana.png", VIEJO, false);
        objeto("reportes/r1/subiendo.jpg", RECIENTE, false);      // su transacción puede estar en curso
        objeto("otros/ajeno.jpg", VIEJO, false);                  // no lo escribe Persistencia

        var r = limpieza().ejecutar();

        assertThat(almacen.objetos).doesNotContainKeys("reportes/r9/huerfana.jpg", "denuncias/d1/huerfana.png")
                .containsKeys("reportes/r1/subiendo.jpg", "otros/ajeno.jpg", "reportes/r0/foto.jpg");
        assertThat(r).isEqualTo(new LimpiezaAlmacen.Resultado(23, 1, 2, 2, 0, false));
    }

    @Test
    void siCasiTodoParecerHuerfanoNoBorraNadaPorqueEsUnaConfiguracionCruzada() {
        objeto("reportes/r1/foto.jpg", VIEJO, true);
        for (int i = 0; i < 9; i++) {
            objeto("reportes/x" + i + "/foto.jpg", VIEJO, false);   // p. ej., base vacía junto a un bucket lleno
        }

        var r = limpieza().ejecutar();

        assertThat(r.frenada()).isTrue();
        assertThat(r.borrados()).isZero();
        assertThat(almacen.objetos).hasSize(10);
    }

    @Test
    void pocosHuerfanosNoActivanElFreno() {
        objeto("reportes/r1/huerfana.jpg", VIEJO, false);
        objeto("reportes/r2/huerfana.jpg", VIEJO, false);

        var r = limpieza().ejecutar();

        assertThat(r.frenada()).isFalse();
        assertThat(almacen.objetos).isEmpty();
    }

    @Test
    void siSeReferencioMientrasTantoNoLoBorra() {
        for (int i = 0; i < 10; i++) {
            objeto("reportes/r" + i + "/foto.jpg", VIEJO, true);
        }
        objeto("reportes/r1/tardia.jpg", VIEJO, false);
        almacen.alListar = () -> base.claves.add("reportes/r1/tardia.jpg");

        var r = limpieza().ejecutar();

        assertThat(almacen.objetos).containsKey("reportes/r1/tardia.jpg");
        assertThat(r.huerfanos()).isEqualTo(1);
        assertThat(r.borrados()).isZero();
    }

    @Test
    void unaFallaAlBorrarNoDetieneLasDemas() {
        for (int i = 0; i < 10; i++) {
            objeto("reportes/r" + i + "/foto.jpg", VIEJO, true);
        }
        objeto("reportes/a/huerfana.jpg", VIEJO, false);
        objeto("reportes/b/huerfana.jpg", VIEJO, false);
        almacen.quebradizos.add("reportes/a/huerfana.jpg");

        var r = limpieza().ejecutar();

        assertThat(r.borrados()).isEqualTo(1);
        assertThat(r.fallidos()).isEqualTo(1);
        assertThat(almacen.objetos).containsKey("reportes/a/huerfana.jpg").doesNotContainKey("reportes/b/huerfana.jpg");
    }
}
