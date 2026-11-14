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
 */
public interface RegistroIdempotencia {

    /**
     * Reclama la clave. Si ya fue procesada devuelve el identificador del recurso
     * que produjo la primera vez; si es nueva devuelve vacío.
     */
    Optional<UUID> reclamar(IdCorrelacion correlacion, OperacionNegocio operacion, ServicioCliente servicio);

    void registrarResultado(IdCorrelacion correlacion, OperacionNegocio operacion, UUID idRecurso);
}
