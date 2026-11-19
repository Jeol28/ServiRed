package co.edu.javeriana.servired.persistencia.aplicacion.puertos;

import java.util.List;
import co.edu.javeriana.servired.persistencia.dominio.Cuentas.AccesoRevocado;
import co.edu.javeriana.servired.persistencia.dominio.Cuentas.SesionNueva;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RepositorioSesiones {

    void insertar(SesionNueva sesion);

    /** Bloquea la sesión (FOR UPDATE) para que dos rotaciones no compitan. */
    Optional<SesionVigente> bloquearPorHash(String hashRefresco);

    /** Rotación: la sesión queda reemplazada; su token de acceso sigue vigente hasta expirar. */
    void revocar(UUID idSesion, UUID reemplazadaPor);

    /**
     * Cierre de sesión: revoca la sesión y los tokens de acceso aún vigentes de ella y de las
     * que reemplazó (la cadena de rotaciones de ese dispositivo).
     */
    void cerrar(UUID idSesion);

    /** Revoca todas las sesiones de la cuenta y todos sus tokens de acceso aún vigentes. */
    void revocarTodas(UUID idCuenta);

    /** Accesos revocados y no expirados; con desde, solo los revocados desde ese momento. */
    List<AccesoRevocado> accesosRevocados(Instant desde);

    record SesionVigente(UUID idSesion, UUID idCuenta, Instant expiraEn, boolean revocada) {
    }
}
