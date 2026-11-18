package co.edu.javeriana.servired.persistencia.infraestructura.mensajeria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SondaColasTest {

    private final AtomicLong ahoraMs = new AtomicLong(5_000_000);
    private final Clock reloj = new Clock() {
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId z) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(ahoraMs.get()); }
    };
    private final PublicadorConfirmado publicador = mock(PublicadorConfirmado.class);
    private final SimpleMeterRegistry metricas = new SimpleMeterRegistry();
    private final SondaColas sonda = new SondaColas(publicador, metricas, reloj);

    private double segundosDesdeLaUltima() {
        return metricas.get("servired.sonda.ultima.publicacion").gauge().value();
    }

    @Test
    void publicaLaSondaConSuHoraYLoRegistra() {
        ahoraMs.addAndGet(7_000);
        sonda.publicar();
        verify(publicador).publicarSonda(5_007_000);
        assertThat(segundosDesdeLaUltima()).isZero();
    }

    @Test
    void siNoPuedePublicarElTiempoDesdeLaUltimaSigueCreciendo() {
        doThrow(new IllegalStateException("broker caído")).when(publicador).publicarSonda(anyLong());
        ahoraMs.addAndGet(4_000);
        sonda.publicar();
        assertThat(segundosDesdeLaUltima()).isEqualTo(4.0);
    }
}
