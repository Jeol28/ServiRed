package co.edu.javeriana.servired.identidad.aplicacion.puertos;

import co.edu.javeriana.servired.identidad.dominio.Rol;
import java.time.Instant;
import java.util.UUID;

/** Emite el token de acceso firmado (JWT) que validan el gateway y los servicios. */
public interface EmisorTokens {

    /** @param cuentaCreadaEn va en el claim "creada": Reportes no cuenta los votos de cuentas nuevas (ADR-22) */
    TokenAcceso emitir(UUID idCuenta, Rol rol, UUID idEmpresa, Instant cuentaCreadaEn);

    record TokenAcceso(String valor, Instant expiraEn) {
    }
}
