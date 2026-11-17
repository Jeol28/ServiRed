package co.edu.javeriana.servired.identidad.aplicacion.puertos;

import co.edu.javeriana.servired.identidad.dominio.Cuenta;
import co.edu.javeriana.servired.identidad.dominio.IdCorrelacion;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Sesiones de renovación en Persistencia; solo viaja el hash del token de refresco. */
public interface RepositorioSesiones {

    void abrir(UUID idSesion, UUID idCuenta, String hashRefresco, Instant expiraEn, IdCorrelacion correlacion);

    /** Rota la sesión: la cuenta si el token era válido; si no, si fue por reutilización (posible robo). */
    ResultadoRotacion rotar(String hashActual, UUID idSesionNueva, String hashNuevo, Instant expiraEn,
                            IdCorrelacion correlacion);

    record ResultadoRotacion(Optional<Cuenta> cuenta, boolean reutilizado) {
        public static ResultadoRotacion valida(Cuenta c) {
            return new ResultadoRotacion(Optional.of(c), false);
        }

        public static ResultadoRotacion invalida(boolean reutilizado) {
            return new ResultadoRotacion(Optional.empty(), reutilizado);
        }
    }

    void cerrar(String hashRefresco, IdCorrelacion correlacion);
}
