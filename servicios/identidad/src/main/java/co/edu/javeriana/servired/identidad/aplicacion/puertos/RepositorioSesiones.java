package co.edu.javeriana.servired.identidad.aplicacion.puertos;

import co.edu.javeriana.servired.identidad.dominio.Cuenta;
import co.edu.javeriana.servired.identidad.dominio.IdCorrelacion;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Sesiones de renovación en Persistencia; solo viaja el hash del token de refresco. */
public interface RepositorioSesiones {

    void abrir(UUID idSesion, UUID idCuenta, String hashRefresco, Instant expiraEn, IdCorrelacion correlacion);

    /** Rota la sesión: devuelve la cuenta si el token era válido, o vacío. */
    Optional<Cuenta> rotar(String hashActual, UUID idSesionNueva, String hashNuevo, Instant expiraEn,
                           IdCorrelacion correlacion);

    void cerrar(String hashRefresco, IdCorrelacion correlacion);
}
