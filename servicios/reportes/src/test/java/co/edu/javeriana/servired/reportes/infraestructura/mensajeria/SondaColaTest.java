package co.edu.javeriana.servired.reportes.infraestructura.mensajeria;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class SondaColaTest {

    private final AtomicLong ahoraMs = new AtomicLong(1_000_000);
    private final Clock reloj = new Clock() {
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(ahoraMs.get()); }
    };
    private final SimpleMeterRegistry metricas = new SimpleMeterRegistry();
    private final SondaCola sonda = new SondaCola("reportes.eventos-casos", metricas, reloj, 1_000_000);

    private static Message mensaje(String routingKey, Long publicadaMs) {
        var p = new MessageProperties();
        p.setReceivedRoutingKey(routingKey);
        if (publicadaMs != null) {
            p.setHeader(SondaCola.CABECERA, publicadaMs);
        }
        return new Message("{}".getBytes(), p);
    }

    private double edad() {
        return metricas.get("servired.cola.mensaje.mas.viejo").tag("cola", "reportes.eventos-casos").gauge().value();
    }

    @Test
    void reconoceLaSondaYNoLosEventosDeNegocio() {
        assertThat(SondaCola.esSonda(mensaje("sonda.v1", 1L))).isTrue();
        assertThat(SondaCola.esSonda(mensaje("caso.actualizado.v1", null))).isFalse();
    }

    @Test
    void laEdadCreceSiNoLleganSondasYSeAcotaConLaUltimaConsumida() {
        ahoraMs.addAndGet(12_000);
        assertThat(edad()).as("sin sondas desde el arranque").isEqualTo(12.0);

        // Llega una sonda publicada hace 3 s: esperó 3 s y lo pendiente es más nuevo que ella.
        assertThat(sonda.recibir(mensaje("sonda.v1", ahoraMs.get() - 3_000))).contains(3.0);
        assertThat(edad()).isEqualTo(3.0);

        // Una sonda más vieja que llega tarde (otra réplica) no hace retroceder la cota.
        sonda.recibir(mensaje("sonda.v1", ahoraMs.get() - 9_000));
        assertThat(edad()).isEqualTo(3.0);

        ahoraMs.addAndGet(5_000);
        assertThat(edad()).isEqualTo(8.0);
        assertThat(metricas.get("servired.cola.espera.sonda").summary().count()).isEqualTo(2);
    }

    @Test
    void unaColaAtrasadaAlArrancarSeMideConLaSondaAunqueSeaAnteriorAlArranque() {
        // La primera sonda consumida se publicó 1 h antes del arranque: la cola estaba atrasada.
        sonda.recibir(mensaje("sonda.v1", 1_000_000 - 3_600_000L));
        assertThat(edad()).isEqualTo(3600.0);
    }

    @Test
    void unaSondaSinHoraSeIgnora() {
        assertThat(sonda.recibir(mensaje("sonda.v1", null))).isEmpty();
    }
}
