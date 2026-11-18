package co.edu.javeriana.servired.persistencia.infraestructura.jdbc;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RegistroIdempotencia;
import co.edu.javeriana.servired.persistencia.dominio.Conflicto;
import co.edu.javeriana.servired.persistencia.dominio.IdCorrelacion;
import co.edu.javeriana.servired.persistencia.dominio.OperacionNegocio;
import co.edu.javeriana.servired.persistencia.dominio.ServicioCliente;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
    public Optional<UUID> reclamar(IdCorrelacion correlacion, OperacionNegocio operacion, ServicioCliente servicio,
                                   String titular) {
        String huella = titular == null ? null : huella(titular);
        int insertadas = jdbc.sql("""
                        INSERT INTO infraestructura.solicitudes_procesadas
                            (correlation_id, operacion, servicio_origen, respuesta, huella)
                        VALUES (:correlacion, :operacion, :servicio, '{}'::jsonb, :huella)
                        ON CONFLICT (correlation_id, operacion) DO NOTHING
                        """)
                .param("correlacion", UUID.fromString(correlacion.valor()))
                .param("operacion", operacion.nombre())
                .param("servicio", servicio.nombre())
                .param("huella", huella)
                .update();
        if (insertadas == 1) {
            return Optional.empty();
        }
        var previa = jdbc.sql("""
                        SELECT respuesta ->> 'id' AS id, servicio_origen, huella
                        FROM infraestructura.solicitudes_procesadas
                        WHERE correlation_id = :correlacion AND operacion = :operacion
                        """)
                .param("correlacion", UUID.fromString(correlacion.valor()))
                .param("operacion", operacion.nombre())
                .query((rs, n) -> new String[]{rs.getString("id"), rs.getString("servicio_origen"), rs.getString("huella")})
                .optional();
        if (previa.isEmpty()) {
            return Optional.empty();
        }
        String[] p = previa.get();
        // Las filas anteriores a la huella (null) no se comparan.
        boolean ajena = !servicio.nombre().equals(p[1]) || (p[2] != null && !p[2].equals(huella));
        if (ajena) {
            throw new Conflicto("CORRELACION_AJENA",
                    "El X-Correlation-Id ya se usó en otra solicitud: envíe uno nuevo");
        }
        return Optional.ofNullable(p[0]).map(UUID::fromString);
    }

    static String huella(String titular) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(titular.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
