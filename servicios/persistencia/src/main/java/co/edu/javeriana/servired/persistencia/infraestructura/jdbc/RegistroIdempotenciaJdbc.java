package co.edu.javeriana.servired.persistencia.infraestructura.jdbc;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RegistroIdempotencia;
import co.edu.javeriana.servired.persistencia.dominio.IdCorrelacion;
import co.edu.javeriana.servired.persistencia.dominio.OperacionNegocio;
import co.edu.javeriana.servired.persistencia.dominio.ServicioCliente;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Tabla infraestructura.solicitudes_procesadas. El INSERT ... ON CONFLICT
 * bloquea a un duplicado concurrente hasta que la primera transacción
 * confirme; entonces el duplicado ve la fila y devuelve el resultado original.
 */
@Component
public class RegistroIdempotenciaJdbc implements RegistroIdempotencia {

    private final JdbcClient jdbc;

    public RegistroIdempotenciaJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> reclamar(IdCorrelacion correlacion, OperacionNegocio operacion, ServicioCliente servicio) {
        int insertadas = jdbc.sql("""
                        INSERT INTO infraestructura.solicitudes_procesadas
                            (correlation_id, operacion, servicio_origen, respuesta)
                        VALUES (:correlacion, :operacion, :servicio, '{}'::jsonb)
                        ON CONFLICT (correlation_id, operacion) DO NOTHING
                        """)
                .param("correlacion", UUID.fromString(correlacion.valor()))
                .param("operacion", operacion.nombre())
                .param("servicio", servicio.nombre())
                .update();
        if (insertadas == 1) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        SELECT respuesta ->> 'id' FROM infraestructura.solicitudes_procesadas
                        WHERE correlation_id = :correlacion AND operacion = :operacion
                        """)
                .param("correlacion", UUID.fromString(correlacion.valor()))
                .param("operacion", operacion.nombre())
                .query(String.class)
                .optional()
                .map(UUID::fromString);
    }

    @Override
    public void registrarResultado(IdCorrelacion correlacion, OperacionNegocio operacion, UUID idRecurso) {
        jdbc.sql("""
                        UPDATE infraestructura.solicitudes_procesadas
                        SET respuesta = jsonb_build_object('id', CAST(:id AS text))
                        WHERE correlation_id = :correlacion AND operacion = :operacion
                        """)
                .param("id", idRecurso.toString())
                .param("correlacion", UUID.fromString(correlacion.valor()))
                .param("operacion", operacion.nombre())
                .update();
    }
}
