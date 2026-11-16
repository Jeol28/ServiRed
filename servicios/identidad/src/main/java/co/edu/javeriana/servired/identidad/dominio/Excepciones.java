package co.edu.javeriana.servired.identidad.dominio;

/** Excepciones del dominio de identidad. Sus mensajes nunca incluyen datos personales. */
public final class Excepciones {

    private Excepciones() {
    }

    /** Correo o clave incorrectos, o cuenta inactiva (no se distingue, para no revelar cuentas). */
    public static class CredencialesInvalidas extends RuntimeException {
        public CredencialesInvalidas() {
            super("Credenciales inválidas");
        }
    }

    public static class SesionInvalida extends RuntimeException {
        public SesionInvalida() {
            super("La sesión no es válida o expiró");
        }
    }

    public static class CorreoYaRegistrado extends RuntimeException {
        public CorreoYaRegistrado() {
            super("El correo ya está registrado");
        }
    }

    public static class CuentaNoEncontrada extends RuntimeException {
        public CuentaNoEncontrada() {
            super("La cuenta no existe");
        }
    }

    /** La regla de negocio impide la operación aunque el rol tenga el permiso. */
    public static class OperacionNoPermitida extends RuntimeException {
        public OperacionNoPermitida(String motivo) {
            super(motivo);
        }
    }
}
