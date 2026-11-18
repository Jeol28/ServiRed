package co.edu.javeriana.servired.persistencia.aplicacion.puertos;

import co.edu.javeriana.servired.persistencia.dominio.IdCorrelacion;
import co.edu.javeriana.servired.persistencia.dominio.OperacionNegocio;
import co.edu.javeriana.servired.persistencia.dominio.ServicioCliente;
import java.util.Optional;
import java.util.UUID;

/**
 * Idempotencia por (X-Correlation-Id, operación). Debe usarse dentro de la
 * transacción de la operación: reclamar la clave bloquea a un duplicado
 * concurrente hasta que la primera termine.
 * <p>
 * La clave pertenece a quien hizo la primera solicitud (ADR-23): se guarda una huella
 * del {@code titular} (cuenta y recurso). Si la repetición llega con otro titular u
 * otro servicio no es un reintento: se lanza {@code Conflicto("CORRELACION_AJENA")}.
 */
public interface RegistroIdempotencia {

    /**
     * Reclama la clave. Si ya fue procesada por el mismo titular devuelve el
     * identificador del recurso que produjo la primera vez; si es nueva devuelve vacío.
     *
     * @param titular quién pide y sobre qué (p. ej. "ciudadano:&lt;id&gt;|reporte:&lt;id&gt;"); null
     *                solo si la correlación nunca viene del cliente (denuncias anónimas)
     * @throws co.edu.javeriana.servired.persistencia.dominio.Conflicto CORRELACION_AJENA si
     *         la clave ya la usó otro titular u otro servicio
     */
    Optional<UUID> reclamar(IdCorrelacion correlacion, OperacionNegocio operacion, ServicioCliente servicio,
                            String titular);

    void registrarResultado(IdCorrelacion correlacion, OperacionNegocio operacion, UUID idRecurso);
}
