package co.edu.javeriana.servired.persistencia.aplicacion.puertos;

import co.edu.javeriana.servired.persistencia.dominio.Cuentas.SesionNueva;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RepositorioSesiones {

    void insertar(SesionNueva sesion);

    /** Bloquea la sesión (FOR UPDATE) para que dos rotaciones no compitan. */
    Optional<SesionVigente> bloquearPorHash(String hashRefresco);

    void revocar(UUID idSesion, UUID reemplazadaPor);

    void revocarTodas(UUID idCuenta);

    record SesionVigente(UUID idSesion, UUID idCuenta, Instant expiraEn, boolean revocada) {
    }
}
