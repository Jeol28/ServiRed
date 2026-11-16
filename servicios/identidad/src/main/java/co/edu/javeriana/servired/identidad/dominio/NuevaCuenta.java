package co.edu.javeriana.servired.identidad.dominio;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Cuenta validada lista para registrar. Un ciudadano se registra solo y debe
 * autorizar el tratamiento de sus datos (Ley 1581 de 2012); las cuentas del
 * personal (operador, regulador, administrador) las crea un administrador.
 */
public record NuevaCuenta(UUID idCuenta, Correo correo, Rol rol, UUID idEmpresa, String nombre,
                          Instant consentimientoDatosEn, Instant creadaEn) {

    public static final int LONGITUD_MAXIMA_NOMBRE = 120;

    public static NuevaCuenta ciudadano(String correo, String clave, String nombre, boolean aceptaTratamientoDatos,
                                        Instant ahora) {
        List<ErrorDeCampo> errores = new ArrayList<>();
        Correo c = validarComunes(correo, clave, nombre, errores);
        if (!aceptaTratamientoDatos) {
            errores.add(new ErrorDeCampo("aceptaTratamientoDatos", "debe autorizar el tratamiento de datos"));
        }
        lanzarSiHay(errores);
        return new NuevaCuenta(UUID.randomUUID(), c, Rol.CIUDADANO, null, limpiar(nombre), ahora, ahora);
    }

    public static NuevaCuenta personal(String correo, String clave, String nombre, Rol rol, UUID idEmpresa,
                                       Instant ahora) {
        List<ErrorDeCampo> errores = new ArrayList<>();
        Correo c = validarComunes(correo, clave, nombre, errores);
        if (rol == null) {
            errores.add(new ErrorDeCampo("rol", "es obligatorio"));
        } else if (rol.requiereEmpresa() && idEmpresa == null) {
            errores.add(new ErrorDeCampo("idEmpresa", "es obligatoria para un operador"));
        } else if (!rol.requiereEmpresa() && idEmpresa != null) {
            errores.add(new ErrorDeCampo("idEmpresa", "solo aplica a operadores"));
        }
        lanzarSiHay(errores);
        return new NuevaCuenta(UUID.randomUUID(), c, rol, idEmpresa, limpiar(nombre), null, ahora);
    }

    private static Correo validarComunes(String correo, String clave, String nombre, List<ErrorDeCampo> errores) {
        Correo c = null;
        try {
            c = Correo.de(correo);
        } catch (DatosInvalidos e) {
            errores.addAll(e.errores());
        }
        try {
            PoliticaClave.validar(clave, c);
        } catch (DatosInvalidos e) {
            errores.addAll(e.errores());
        }
        validarNombre(nombre, errores);
        return c;
    }

    static void validarNombre(String nombre, List<ErrorDeCampo> errores) {
        if (nombre != null && nombre.strip().length() > LONGITUD_MAXIMA_NOMBRE) {
            errores.add(new ErrorDeCampo("nombre", "máximo " + LONGITUD_MAXIMA_NOMBRE + " caracteres"));
        }
    }

    static String limpiar(String nombre) {
        return nombre == null || nombre.isBlank() ? null : nombre.strip();
    }

    private static void lanzarSiHay(List<ErrorDeCampo> errores) {
        if (!errores.isEmpty()) {
            throw new DatosInvalidos(errores);
        }
    }
}
