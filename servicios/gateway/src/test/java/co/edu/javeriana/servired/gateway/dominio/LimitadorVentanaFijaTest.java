package co.edu.javeriana.servired.gateway.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LimitadorVentanaFijaTest {

    private final AtomicLong ahora = new AtomicLong(Instant.parse("2026-11-16T10:00:00Z").toEpochMilli());
    private final Clock reloj = new Clock() {
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(java.time.ZoneId z) {
            return this;
        }

        public Instant instant() {
            return Instant.ofEpochMilli(ahora.get());
        }
    };

    @Test
    void permiteHastaElMaximoPorClaveYVentana() {
        var limitador = new LimitadorVentanaFija(2, Duration.ofMinutes(1), reloj);

        assertThat(limitador.intentar("a").permitido()).isTrue();
        assertThat(limitador.intentar("a").permitido()).isTrue();
        var tercero = limitador.intentar("a");
        assertThat(tercero.permitido()).isFalse();
        assertThat(tercero.segundosParaReintentar()).isEqualTo(60);
        assertThat(limitador.intentar("b").permitido()).isTrue();
    }

    @Test
    void laVentanaSiguienteEmpiezaDeCero() {
        var limitador = new LimitadorVentanaFija(1, Duration.ofMinutes(1), reloj);
        limitador.intentar("a");
        assertThat(limitador.intentar("a").permitido()).isFalse();

        ahora.addAndGet(Duration.ofSeconds(61).toMillis());

        assertThat(limitador.intentar("a").permitido()).isTrue();
    }
}
