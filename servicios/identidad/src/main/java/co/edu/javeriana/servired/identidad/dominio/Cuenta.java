package co.edu.javeriana.servired.identidad.dominio;

import java.time.Instant;
import java.util.UUID;

/** Cuenta de un usuario de SERVI-RED (RF-11). Nunca contiene la clave ni su hash. */
public record Cuenta(UUID idCuenta, Correo correo, Rol rol, UUID idEmpresa, String nombre, boolean activa,
                     Instant creadaEn) {
}
