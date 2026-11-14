package co.edu.javeriana.servired.persistencia.infraestructura.jdbc;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.Outbox;
import co.edu.javeriana.servired.persistencia.dominio.EventoSaliente;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class OutboxJdbc implements Outbox {

    private final JdbcClient jdbc;

    public OutboxJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void agregar(EventoSaliente evento) {
        jdbc.sql("""
                        INSERT INTO infraestructura.outbox
                            (id_evento, tipo, version, routing_key, correlation_id, servicio_origen, sobre)
                        VALUES (:id, :tipo, :version, :rk, :correlacion, :origen, CAST(:sobre AS jsonb))
                        """)
                .param("id", evento.idEvento())
                .param("tipo", evento.tipo())
                .param("version", evento.version())
                .param("rk", evento.routingKey())
                .param("correlacion", evento.correlationId())
                .param("origen", evento.origen())
                .param("sobre", evento.sobre())
                .update();
    }
}
