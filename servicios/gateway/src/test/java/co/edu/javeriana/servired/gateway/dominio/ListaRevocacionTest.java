package co.edu.javeriana.servired.gateway.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.javeriana.servired.gateway.dominio.ListaRevocacion.Entrada;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ListaRevocacionTest {

    private static final Instant T0 = Instant.parse("2026-11-18T12:00:00Z");
    private final ListaRevocacion lista = new ListaRevocacion();

    @Test
    void rechazaElJtiHastaQueExpiraYLuegoLoOlvida() {
        lista.aplicar(List.of(new Entrada("a", T0.plusSeconds(600))), T0, T0);

        assertThat(lista.revocado("a", T0.plusSeconds(599))).isTrue();
        assertThat(lista.revocado("a", T0.plusSeconds(600))).isFalse();
        assertThat(lista.revocado("b", T0)).isFalse();
        assertThat(lista.revocado(null, T0)).isFalse();

        lista.aplicar(List.of(), T0.plusSeconds(700), T0.plusSeconds(700));
        assertThat(lista.tamano()).isZero();
    }

    @Test
    void laPrimeraConsultaPideTodoYLasSiguientesDesdeLaHoraDeIdentidadConSolapamiento() {
        assertThat(lista.proximoDesde(Duration.ofSeconds(30))).isNull();
        assertThat(lista.ultimaSincronizacion()).isNull();

        lista.aplicar(List.of(), T0, T0.plusSeconds(2));

        assertThat(lista.proximoDesde(Duration.ofSeconds(30))).isEqualTo(T0.minusSeconds(30));
        assertThat(lista.ultimaSincronizacion()).isEqualTo(T0.plusSeconds(2));
    }

    @Test
    void repetirUnaEntradaPorElSolapamientoNoCambiaNada() {
        var e = new Entrada("a", T0.plusSeconds(600));
        lista.aplicar(List.of(e), T0, T0);
        lista.aplicar(List.of(e, new Entrada("a", T0.plusSeconds(300))), T0.plusSeconds(5), T0.plusSeconds(5));

        assertThat(lista.tamano()).isEqualTo(1);
        assertThat(lista.revocado("a", T0.plusSeconds(500))).as("conserva la expiración más tardía").isTrue();
    }
}
