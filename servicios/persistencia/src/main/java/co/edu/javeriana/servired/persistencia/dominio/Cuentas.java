package co.edu.javeriana.servired.persistencia.dominio;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Modelos de las operaciones de cuentas y sesiones (dominio de Identidad).
 * Persistencia no valida reglas de identidad: las aplica el servicio dueño.
 */
public final class Cuentas {

    private Cuentas() {
    }

    public record CuentaNueva(UUID idCuenta, String correo, String hashClave, String rol, UUID idEmpresa,
                              String nombre, Instant consentimientoDatosEn, Instant creadaEn) {
    }

    /** Cuenta sin su hash de clave: es lo único que sale de Persistencia salvo en buscarCredenciales. */
    public record Cuenta(UUID idCuenta, String correo, String rol, UUID idEmpresa, String nombre, boolean activa,
                         Instant creadaEn) {
    }

    /** creadaEn va en el JWT (claim "creada"): la antigüedad de la cuenta cuenta para validar (ADR-22). */
    public record Credenciales(UUID idCuenta, String hashClave, String rol, UUID idEmpresa, String nombre,
                               boolean activa, Instant creadaEn) {
    }

    /** Cambios parciales: los campos nulos no se modifican. */
    public record CambiosCuenta(String nombre, Boolean activa, UUID idEmpresa, String hashClave) {

        public boolean invalidaSesiones() {
            return Boolean.FALSE.equals(activa) || hashClave != null;
        }
    }

    public record Pagina(List<Cuenta> elementos, long total, int pagina, int tamano) {
    }

    public record SesionNueva(UUID idSesion, UUID idCuenta, String hashRefresco, Instant expiraEn) {
    }

    public record Rotacion(String hashRefrescoActual, UUID idSesionNueva, String hashRefrescoNuevo,
                           Instant expiraEn) {
    }

    /** Resultado de rotar: la cuenta si la sesión era válida, o el motivo del rechazo. */
    public record ResultadoRotacion(Cuenta cuenta, MotivoRechazo motivo) {

        public static ResultadoRotacion valida(Cuenta cuenta) {
            return new ResultadoRotacion(cuenta, null);
        }

        public static ResultadoRotacion rechazada(MotivoRechazo motivo) {
            return new ResultadoRotacion(null, motivo);
        }

        public boolean esValida() {
            return cuenta != null;
        }
    }

    public enum MotivoRechazo {
        INEXISTENTE, EXPIRADA, CUENTA_INACTIVA,
        /** Se presentó un token ya rotado: posible robo; se revocan todas las sesiones de la cuenta. */
        REUTILIZADA
    }
}
