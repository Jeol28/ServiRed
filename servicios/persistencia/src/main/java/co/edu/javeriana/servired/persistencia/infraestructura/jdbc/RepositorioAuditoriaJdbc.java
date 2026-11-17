package co.edu.javeriana.servired.persistencia.infraestructura.jdbc;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioAuditoria;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.Filtro;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.Pagina;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.Registro;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.RegistroNuevo;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.Verificacion;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Esquema auditoria. El encadenamiento se calcula en la base de datos con
 * auditoria.huella() bajo un bloqueo consultivo de transacción, así dos
 * registros concurrentes nunca toman el mismo "anterior".
 */
@Component
public class RepositorioAuditoriaJdbc implements RepositorioAuditoria {

    private final JdbcClient jdbc;

    public RepositorioAuditoriaJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean registrar(RegistroNuevo r) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('auditoria.registros'))").query(Object.class).single();
        return jdbc.sql("""
                        INSERT INTO auditoria.registros
                            (id_evento, tipo, version, origen, correlation_id, categoria, accion, resultado, actor_id,
                             actor_rol, recurso_tipo, recurso_id, detalle, ocurrido_en, hash_anterior, hash)
                        SELECT :id, :tipo, :version, :origen, :correlacion, :categoria, :accion, :resultado, :actor,
                               :rol, :recursoTipo, :recursoId, CAST(:detalle AS jsonb), :ocurrido, previo.hash,
                               auditoria.huella(previo.hash, :id, :tipo, :origen, :categoria, :accion, :resultado,
                                                :actor, :rol, :recursoTipo, :recursoId, CAST(:detalle AS jsonb),
                                                CAST(:ocurrido AS timestamptz))
                        FROM (SELECT (SELECT hash FROM auditoria.registros ORDER BY secuencia DESC LIMIT 1) AS hash) previo
                        ON CONFLICT (id_evento) DO NOTHING
                        """)
                .param("id", r.idEvento()).param("tipo", r.tipo()).param("version", r.version())
                .param("origen", r.origen()).param("correlacion", r.correlationId()).param("categoria", r.categoria())
                .param("accion", r.accion()).param("resultado", r.resultado()).param("actor", r.actorId())
                .param("rol", r.actorRol()).param("recursoTipo", r.recursoTipo()).param("recursoId", r.recursoId())
                .param("detalle", r.detalle() == null ? "{}" : r.detalle())
                .param("ocurrido", Timestamp.from(r.ocurridoEn()))
                .update() == 1;
    }

    @Override
    public Pagina consultar(Filtro f) {
        String donde = """
                 WHERE (CAST(:categoria AS text) IS NULL OR categoria = :categoria)
                   AND (CAST(:tipo AS text) IS NULL OR tipo = :tipo)
                   AND (CAST(:accion AS text) IS NULL OR accion = :accion)
                   AND (CAST(:actor AS uuid) IS NULL OR actor_id = :actor)
                   AND (CAST(:recurso AS text) IS NULL OR recurso_id = :recurso)
                   AND (CAST(:desde AS timestamptz) IS NULL OR ocurrido_en >= :desde)
                   AND (CAST(:hasta AS timestamptz) IS NULL OR ocurrido_en < :hasta)
                """;
        int tamano = Math.clamp(f.tamano(), 1, 200);
        int pagina = Math.max(0, f.pagina());
        long total = parametros(jdbc.sql("SELECT count(*) FROM auditoria.registros" + donde), f)
                .query(Long.class).single();
        var elementos = parametros(jdbc.sql("SELECT *, CAST(detalle AS text) AS detalle_texto FROM auditoria.registros"
                        + donde + " ORDER BY secuencia DESC LIMIT :limite OFFSET :offset"), f)
                .param("limite", tamano).param("offset", (long) pagina * tamano)
                .query((rs, n) -> new Registro(rs.getLong("secuencia"), rs.getObject("id_evento", UUID.class),
                        rs.getString("tipo"), rs.getInt("version"), rs.getString("origen"),
                        rs.getObject("correlation_id", UUID.class), rs.getString("categoria"), rs.getString("accion"),
                        rs.getString("resultado"), rs.getObject("actor_id", UUID.class), rs.getString("actor_rol"),
                        rs.getString("recurso_tipo"), rs.getString("recurso_id"), rs.getString("detalle_texto"),
                        rs.getTimestamp("ocurrido_en").toInstant(), rs.getTimestamp("registrado_en").toInstant(),
                        rs.getString("hash")))
                .list();
        return new Pagina(elementos, total, pagina, tamano);
    }

    private static JdbcClient.StatementSpec parametros(JdbcClient.StatementSpec sql, Filtro f) {
        return sql.param("categoria", f.categoria()).param("tipo", f.tipo()).param("accion", f.accion())
                .param("actor", f.actorId()).param("recurso", f.recursoId())
                .param("desde", f.desde() == null ? null : Timestamp.from(f.desde()))
                .param("hasta", f.hasta() == null ? null : Timestamp.from(f.hasta()));
    }

    /** Recalcula toda la cadena: cada huella debe coincidir y apuntar al hash del registro anterior. */
    @Override
    public Verificacion verificar() {
        return jdbc.sql("""
                        WITH cadena AS (
                            SELECT secuencia, hash, hash_anterior,
                                   lag(hash) OVER (ORDER BY secuencia) AS anterior_real,
                                   auditoria.huella(hash_anterior, id_evento, tipo, origen, categoria, accion, resultado,
                                                    actor_id, actor_rol, recurso_tipo, recurso_id, detalle,
                                                    ocurrido_en) AS recalculado
                            FROM auditoria.registros)
                        SELECT count(*) AS registros,
                               min(secuencia) FILTER (WHERE hash <> recalculado
                                   OR hash_anterior IS DISTINCT FROM anterior_real) AS primera_rota,
                               (SELECT hash FROM auditoria.registros ORDER BY secuencia DESC LIMIT 1) AS ultimo
                        FROM cadena
                        """)
                .query((rs, n) -> {
                    long rota = rs.getLong("primera_rota");
                    boolean hayRota = !rs.wasNull();
                    return new Verificacion(rs.getLong("registros"), !hayRota, hayRota ? rota : null,
                            rs.getString("ultimo"));
                })
                .single();
    }
}
