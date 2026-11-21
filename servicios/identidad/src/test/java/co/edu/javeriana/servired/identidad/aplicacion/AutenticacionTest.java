package co.edu.javeriana.servired.identidad.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.javeriana.servired.identidad.aplicacion.puertos.CifradorClaves;
import co.edu.javeriana.servired.identidad.aplicacion.puertos.EmisorTokens;
import co.edu.javeriana.servired.identidad.dominio.Excepciones.CorreoYaRegistrado;
import co.edu.javeriana.servired.identidad.dominio.Excepciones.CredencialesInvalidas;
import co.edu.javeriana.servired.identidad.dominio.Excepciones.OperacionNoPermitida;
import co.edu.javeriana.servired.identidad.dominio.Excepciones.SesionInvalida;
import co.edu.javeriana.servired.identidad.dominio.IdCorrelacion;
import co.edu.javeriana.servired.identidad.dominio.Rol;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AutenticacionTest {

    /** Cifrador reversible de prueba: "h:" + clave. */
    static final CifradorClaves CIFRADOR = new CifradorClaves() {
        public String cifrar(String clave) {
            return "h:" + clave;
        }

        public boolean coincide(String clave, String hash) {
            return hash.equals("h:" + clave);
        }
    };

    private final AtomicInteger emitidos = new AtomicInteger();
    /** El token lleva su jti a la vista, para comprobar que es el que se guardó con la sesión. */
    private final EmisorTokens emisor = new EmisorTokens() {
        public TokenAcceso emitir(UUID id, Rol rol, UUID empresa, Instant creada) {
            return emitir(planear(), id, rol, empresa, creada);
        }

        public Plan planear() {
            Instant ahora = Instant.now();
            return new Plan(UUID.randomUUID(), ahora, ahora.plusSeconds(900));
        }

        public TokenAcceso emitir(Plan plan, UUID id, Rol rol, UUID empresa, Instant creada) {
            return new TokenAcceso("token-" + emitidos.incrementAndGet() + "-jti:" + plan.jti() + "-" + rol, plan.expiraEn());
        }
    };
    private final PersistenciaEnMemoria persistencia = new PersistenciaEnMemoria();
    private final Autenticacion auth = new Autenticacion(persistencia, persistencia, CIFRADOR, emisor,
            Clock.systemUTC(), Duration.ofDays(14));
    private final AdministracionCuentas admin = new AdministracionCuentas(persistencia, CIFRADOR, Clock.systemUTC());
    private final IdCorrelacion correlacion = IdCorrelacion.nuevo();

    private void registrar() {
        auth.registrarCiudadano("ana@ejemplo.co", "clave-segura-1", "Ana", true, correlacion);
    }

    @Test
    void registraEIniciaSesionConElCorreoEnCualquierFormato() {
        registrar();

        var sesion = auth.iniciarSesion(" ANA@ejemplo.co", "clave-segura-1", correlacion);

        assertThat(sesion.rol()).isEqualTo(Rol.CIUDADANO);
        assertThat(sesion.acceso().valor()).endsWith("CIUDADANO");
        assertThat(sesion.tokenRefresco()).hasSizeGreaterThan(40);
        assertThat(persistencia.sesiones).doesNotContainKey(sesion.tokenRefresco());
    }

    @Test
    void noPermiteRegistrarDosVecesElMismoCorreo() {
        registrar();

        assertThatThrownBy(this::registrar).isInstanceOf(CorreoYaRegistrado.class);
    }

    @Test
    void lasCredencialesIncorrectasNoRevelanSiLaCuentaExiste() {
        registrar();

        assertThatThrownBy(() -> auth.iniciarSesion("ana@ejemplo.co", "otra-clave-12", correlacion))
                .isInstanceOf(CredencialesInvalidas.class).hasMessage("Credenciales inválidas");
        assertThatThrownBy(() -> auth.iniciarSesion("nadie@ejemplo.co", "clave-segura-1", correlacion))
                .isInstanceOf(CredencialesInvalidas.class).hasMessage("Credenciales inválidas");
        assertThatThrownBy(() -> auth.iniciarSesion("no es correo", "x", correlacion))
                .isInstanceOf(CredencialesInvalidas.class);
    }

    @Test
    void unaCuentaDesactivadaNoIniciaSesion() {
        registrar();
        var id = persistencia.cuentas.keySet().iterator().next();
        persistencia.actualizar(id, new co.edu.javeriana.servired.identidad.aplicacion.puertos.RepositorioCuentas
                .Cambios(null, false, null, null), correlacion);

        assertThatThrownBy(() -> auth.iniciarSesion("ana@ejemplo.co", "clave-segura-1", correlacion))
                .isInstanceOf(CredencialesInvalidas.class);
    }

    @Test
    void renuevaRotandoElTokenYDetectaLaReutilizacion() {
        registrar();
        var inicial = auth.iniciarSesion("ana@ejemplo.co", "clave-segura-1", correlacion);

        var renovada = auth.renovar(inicial.tokenRefresco(), correlacion);
        assertThat(renovada.tokenRefresco()).isNotEqualTo(inicial.tokenRefresco());
        assertThat(renovada.acceso().valor()).isNotEqualTo(inicial.acceso().valor());

        assertThatThrownBy(() -> auth.renovar(inicial.tokenRefresco(), correlacion)).isInstanceOf(SesionInvalida.class);
        assertThatThrownBy(() -> auth.renovar(renovada.tokenRefresco(), correlacion)).isInstanceOf(SesionInvalida.class);
    }

    @Test
    void cadaSesionGuardaElJtiDelTokenDeAccesoQueSeEmiteConElla() {
        registrar();
        var inicial = auth.iniciarSesion("ana@ejemplo.co", "clave-segura-1", correlacion);
        var renovada = auth.renovar(inicial.tokenRefresco(), correlacion);

        assertThat(persistencia.accesosAbiertos).hasSize(2);
        assertThat(inicial.acceso().valor()).contains("jti:" + persistencia.accesosAbiertos.get(0).jti());
        assertThat(renovada.acceso().valor()).contains("jti:" + persistencia.accesosAbiertos.get(1).jti());
        assertThat(renovada.acceso().expiraEn()).isEqualTo(persistencia.accesosAbiertos.get(1).expiraEn());
    }

    @Test
    void cerrarSesionInvalidaElTokenDeRefresco() {
        registrar();
        var s = auth.iniciarSesion("ana@ejemplo.co", "clave-segura-1", correlacion);

        auth.cerrarSesion(s.tokenRefresco(), correlacion);

        assertThatThrownBy(() -> auth.renovar(s.tokenRefresco(), correlacion)).isInstanceOf(SesionInvalida.class);
        assertThatThrownBy(() -> auth.renovar("basura", correlacion)).isInstanceOf(SesionInvalida.class);
    }

    @Test
    void cambiarLaClaveExigeLaActualYCierraLasSesiones() {
        registrar();
        var s = auth.iniciarSesion("ana@ejemplo.co", "clave-segura-1", correlacion);
        var yo = new Solicitante(s.idCuenta(), Rol.CIUDADANO);

        assertThatThrownBy(() -> admin.actualizarPropia(yo, null, "equivocada-1", "clave-nueva-123", correlacion))
                .isInstanceOf(CredencialesInvalidas.class);
        admin.actualizarPropia(yo, null, "clave-segura-1", "clave-nueva-123", correlacion);

        assertThat(persistencia.sesionesActivas(s.idCuenta())).isZero();
        auth.iniciarSesion("ana@ejemplo.co", "clave-nueva-123", correlacion);
    }

    @Test
    void soloUnAdministradorCreaCuentasDePersonal() {
        var ciudadano = new Solicitante(UUID.randomUUID(), Rol.CIUDADANO);
        var administrador = new Solicitante(UUID.randomUUID(), Rol.ADMINISTRADOR);

        assertThatThrownBy(() -> admin.crear(ciudadano, "re@ejemplo.co", "clave-segura-1", null, Rol.REGULADOR, null,
                correlacion)).isInstanceOf(OperacionNoPermitida.class);
        assertThat(admin.crear(administrador, "re@ejemplo.co", "clave-segura-1", null, Rol.REGULADOR, null,
                correlacion).rol()).isEqualTo(Rol.REGULADOR);
    }



}
