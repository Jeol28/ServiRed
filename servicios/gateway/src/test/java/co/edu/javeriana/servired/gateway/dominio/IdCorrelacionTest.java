package co.edu.javeriana.servired.gateway.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class IdCorrelacionTest {

    @Test
    void conservaUnUuidValidoEnviadoPorElCliente() {
        String enviado = UUID.randomUUID().toString();

        assertThat(IdCorrelacion.desdeCabecera(enviado).valor()).isEqualTo(enviado);
    }

    @Test
    void normalizaAMinusculas() {
        String enviado = UUID.randomUUID().toString().toUpperCase();

        assertThat(IdCorrelacion.desdeCabecera(enviado).valor()).isEqualTo(enviado.toLowerCase());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"abc", "1-1-1-1-1", "<script>alert(1)</script>", "usuario@correo.com"})
    void generaUnoNuevoSiLaCabeceraFaltaOEsInvalida(String enviado) {
        String resultado = IdCorrelacion.desdeCabecera(enviado).valor();

        assertThat(resultado).isNotEqualTo(enviado);
        assertThat(UUID.fromString(resultado)).isNotNull();
    }
}
