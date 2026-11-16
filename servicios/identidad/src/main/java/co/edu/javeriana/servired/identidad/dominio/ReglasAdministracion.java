package co.edu.javeriana.servired.identidad.dominio;

import co.edu.javeriana.servired.identidad.dominio.Excepciones.OperacionNoPermitida;
import java.util.UUID;

/**
 * Reglas de la administración de cuentas que van más allá del rol: un
 * administrador no puede desactivarse ni eliminarse a sí mismo (evita quedar
 * sin administradores por error) y un administrador no se suprime por la vía
 * de autoservicio.
 */
public final class ReglasAdministracion {

    private ReglasAdministracion() {
    }

    public static void validarCambioDeOtraCuenta(UUID administrador, UUID objetivo, Boolean activa, boolean eliminar) {
        if (administrador.equals(objetivo) && (eliminar || Boolean.FALSE.equals(activa))) {
            throw new OperacionNoPermitida("Un administrador no puede desactivar ni eliminar su propia cuenta");
        }
    }

    public static void validarSupresionPropia(Rol rol) {
        if (rol == Rol.ADMINISTRADOR) {
            throw new OperacionNoPermitida("La cuenta de un administrador la elimina otro administrador");
        }
    }

    public static void validarNombre(String nombre) {
        var errores = new java.util.ArrayList<ErrorDeCampo>();
        NuevaCuenta.validarNombre(nombre, errores);
        if (!errores.isEmpty()) {
            throw new DatosInvalidos(errores);
        }
    }
}
