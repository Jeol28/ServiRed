package co.edu.javeriana.servired.identidad.aplicacion.puertos;

import co.edu.javeriana.servired.identidad.dominio.Rol;
import java.time.Instant;
import java.util.UUID;

/** Emite el token de acceso firmado (JWT) que validan el gateway y los servicios. */
public interface EmisorTokens {

    /** @param cuentaCreadaEn va en el claim "creada": Reportes no cuenta los votos de cuentas nuevas (ADR-22) */
    TokenAcceso emitir(UUID idCuenta, Rol rol, UUID idEmpresa, Instant cuentaCreadaEn);

    /**
     * Reserva el jti y la expiración del próximo token de acceso. La sesión los guarda antes de
     * que el token exista (en la rotación, la cuenta llega recién al rotar), para poder
     * revocarlo por jti (ADR-26).
     */
    Plan planear();

    /** Firma el token con el jti y las fechas reservadas. */
    TokenAcceso emitir(Plan plan, UUID idCuenta, Rol rol, UUID idEmpresa, Instant cuentaCreadaEn);

    record Plan(UUID jti, Instant emitidoEn, Instant expiraEn) {
    }

    record TokenAcceso(String valor, Instant expiraEn) {
    }
}
