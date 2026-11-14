package co.edu.javeriana.servired.reportes.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NuevoReporteTest {

    private static final Instant AHORA = Instant.parse("2026-11-14T15:00:00Z");
    private static final Ubicacion BOGOTA = new Ubicacion(4.6287, -74.0648);
    private final UUID ciudadano = UUID.randomUUID();

    private NuevoReporte registrar(Ubicacion u, Instant inicio, String descripcion) {
        return NuevoReporte.registrar(ciudadano, TipoServicio.ENERGIA, u, inicio, Magnitud.CUADRA, descripcion, AHORA);
    }

    @Test
    void registraUnReporteValido() {
        var r = registrar(BOGOTA, AHORA.minus(Duration.ofHours(2)), "  Sin luz desde el mediodía ");

        assertThat(r.idReporte()).isNotNull();
        assertThat(r.fechaRegistro()).isEqualTo(AHORA);
        assertThat(r.descripcion()).isEqualTo("Sin luz desde el mediodía");
    }

    @Test
    void rechazaUnInicioFuturo() {
        assertThatThrownBy(() -> registrar(BOGOTA, AHORA.plus(Duration.ofHours(1)), null))
                .isInstanceOfSatisfying(ReporteInvalido.class, e -> assertThat(e.errores())
                        .containsExactly(new ErrorDeCampo("inicioEstimado", "no puede ser futuro")));
    }

    @Test
    void toleraPequenasDiferenciasDeRelojYAjustaAlPresente() {
        var r = registrar(BOGOTA, AHORA.plus(Duration.ofMinutes(2)), null);

        assertThat(r.inicioEstimado()).isEqualTo(AHORA);
    }

    @Test
    void rechazaCortesDeMasDeTreintaDias() {
        assertThatThrownBy(() -> registrar(BOGOTA, AHORA.minus(Duration.ofDays(31)), null))
                .isInstanceOf(ReporteInvalido.class);
    }

    @Test
    void rechazaUbicacionesFueraDeColombia() {
        assertThatThrownBy(() -> registrar(new Ubicacion(40.4, -3.7), AHORA, null))
                .isInstanceOfSatisfying(ReporteInvalido.class, e -> assertThat(e.errores())
                        .extracting(ErrorDeCampo::campo).containsExactly("ubicacion"));
    }

    @Test
    void aceptaSanAndresYLeticia() {
        registrar(new Ubicacion(12.5847, -81.7006), AHORA, null);
        registrar(new Ubicacion(-4.2153, -69.9406), AHORA, null);
    }

    @Test
    void reuneTodosLosErroresALaVez() {
        assertThatThrownBy(() -> NuevoReporte.registrar(ciudadano, null, null, null, null, "x".repeat(1001), AHORA))
                .isInstanceOfSatisfying(ReporteInvalido.class, e -> assertThat(e.errores())
                        .extracting(ErrorDeCampo::campo)
                        .containsExactly("tipoServicio", "ubicacion", "inicioEstimado", "magnitud", "descripcion"));
    }

    @Test
    void unaDescripcionEnBlancoSeOmite() {
        assertThat(registrar(BOGOTA, AHORA, "   ").descripcion()).isNull();
    }
}
