package co.edu.javeriana.servired.identidad.aplicacion.puertos;

import co.edu.javeriana.servired.identidad.dominio.Rol;
import java.time.Instant;
import java.util.UUID;

/** Emite el token de acceso firmado (JWT) que validan el gateway y los servicios. */
public interface EmisorTokens {

    TokenAcceso emitir(UUID idCuenta, Rol rol, UUID idEmpresa);

    record TokenAcceso(String valor, Instant expiraEn) {
    }
}
