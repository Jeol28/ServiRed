package co.edu.javeriana.servired.identidad.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.javeriana.servired.identidad.dominio.Excepciones.OperacionNoPermitida;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReglasIdentidadTest {

    private static final Instant AHORA = Instant.parse("2026-11-16T12:00:00Z");
    private static final UUID EMPRESA = UUID.randomUUID();

    @Test
    void normalizaElCorreoYNoLoMuestraEnTexto() {
        Correo c = Correo.de("  Ana.Perez@Ejemplo.CO ");

        assertThat(c.valor()).isEqualTo("ana.perez@ejemplo.co");
        assertThat(c.toString()).doesNotContain("ana");
    }

    @Test
    void rechazaCorreosMalFormados() {
        assertThatThrownBy(() -> Correo.de("sin-arroba")).isInstanceOf(DatosInvalidos.class);
        assertThatThrownBy(() -> Correo.de("a@b")).isInstanceOf(DatosInvalidos.class);
        assertThatThrownBy(() -> Correo.de(null)).isInstanceOf(DatosInvalidos.class);
    }

    @Test
    void laClaveDebeSerLargaPeroCaberEnBcrypt() {
        Correo c = Correo.de("ana@ejemplo.co");
        assertThatThrownBy(() -> PoliticaClave.validar("corta", c)).isInstanceOf(DatosInvalidos.class);
        assertThatThrownBy(() -> PoliticaClave.validar("x".repeat(65), c)).isInstanceOf(DatosInvalidos.class);
        assertThatThrownBy(() -> PoliticaClave.validar("ñ".repeat(40), c)).isInstanceOf(DatosInvalidos.class);
        assertThatThrownBy(() -> PoliticaClave.validar("ANA@ejemplo.co", c)).isInstanceOf(DatosInvalidos.class);
        assertThatCode(() -> PoliticaClave.validar("una frase larga y fácil", c)).doesNotThrowAnyException();
    }

    @Test
    void elCiudadanoDebeAutorizarElTratamientoDeDatos() {
        assertThatThrownBy(() -> NuevaCuenta.ciudadano("ana@ejemplo.co", "clave-segura-1", null, false, AHORA))
                .isInstanceOfSatisfying(DatosInvalidos.class, e -> assertThat(e.errores())
                        .extracting(ErrorDeCampo::campo).containsExactly("aceptaTratamientoDatos"));

        var c = NuevaCuenta.ciudadano("ana@ejemplo.co", "clave-segura-1", " Ana ", true, AHORA);
        assertThat(c.rol()).isEqualTo(Rol.CIUDADANO);
        assertThat(c.nombre()).isEqualTo("Ana");
        assertThat(c.consentimientoDatosEn()).isEqualTo(AHORA);
    }

    @Test
    void reuneTodosLosErroresDelRegistro() {
        assertThatThrownBy(() -> NuevaCuenta.ciudadano("malo", "corta", "x".repeat(121), false, AHORA))
                .isInstanceOfSatisfying(DatosInvalidos.class, e -> assertThat(e.errores())
                        .extracting(ErrorDeCampo::campo)
                        .containsExactly("correo", "clave", "nombre", "aceptaTratamientoDatos"));
    }

    @Test
    void soloElOperadorTieneEmpresa() {
        assertThatThrownBy(() -> NuevaCuenta.personal("op@ejemplo.co", "clave-segura-1", null, Rol.OPERADOR, null, AHORA))
                .isInstanceOf(DatosInvalidos.class);
        assertThatThrownBy(() -> NuevaCuenta.personal("re@ejemplo.co", "clave-segura-1", null, Rol.REGULADOR, EMPRESA, AHORA))
                .isInstanceOf(DatosInvalidos.class);
        assertThat(NuevaCuenta.personal("op@ejemplo.co", "clave-segura-1", null, Rol.OPERADOR, EMPRESA, AHORA)
                .idEmpresa()).isEqualTo(EMPRESA);
    }

    @Test
    void unAdministradorNoSeDesactivaNiSeEliminaASiMismo() {
        UUID admin = UUID.randomUUID();

        assertThatThrownBy(() -> ReglasAdministracion.validarCambioDeOtraCuenta(admin, admin, false, false))
                .isInstanceOf(OperacionNoPermitida.class);
        assertThatThrownBy(() -> ReglasAdministracion.validarCambioDeOtraCuenta(admin, admin, null, true))
                .isInstanceOf(OperacionNoPermitida.class);
        assertThatCode(() -> ReglasAdministracion.validarCambioDeOtraCuenta(admin, admin, null, false))
                .doesNotThrowAnyException();
        assertThatCode(() -> ReglasAdministracion.validarCambioDeOtraCuenta(admin, UUID.randomUUID(), false, true))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ReglasAdministracion.validarSupresionPropia(Rol.ADMINISTRADOR))
                .isInstanceOf(OperacionNoPermitida.class);
    }
}
