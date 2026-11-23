package co.edu.javeriana.servired.persistencia.infraestructura.jdbc;

import java.util.Map;
import java.time.LocalDate;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.Ancla;
import java.util.Optional;
import java.util.List;
import java.time.Instant;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.Segmento;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.RegistroArchivado;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.Rango;
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
                        FROM (SELECT coalesce(
                                (SELECT hash FROM auditoria.registros ORDER BY secuencia DESC LIMIT 1),
                                -- Si todo está archivado, el anterior es el último registro archivado (ADR-29).
                                (SELECT hash_final FROM auditoria.segmentos ORDER BY secuencia_hasta DESC LIMIT 1))
                              AS hash) previo
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

    /**
     * Recalcula la cadena que sigue en la base (cada huella y su enlace; el primer registro debe
     * enlazar con el último segmento archivado) y los enlaces entre segmentos (ADR-29).
     */
    @Override
    public Verificacion verificar() {
        return jdbc.sql("""
                        WITH ancla AS (
                            SELECT hash_final, secuencia_hasta FROM auditoria.segmentos
                            ORDER BY secuencia_hasta DESC LIMIT 1),
                        cadena AS (
                            SELECT secuencia, hash, hash_anterior,
                                   CASE WHEN row_number() OVER (ORDER BY secuencia) = 1
                                        THEN (SELECT hash_final FROM ancla)
                                        ELSE lag(hash) OVER (ORDER BY secuencia) END AS anterior_real,
                                   auditoria.huella(hash_anterior, id_evento, tipo, origen, categoria, accion, resultado,
                                                    actor_id, actor_rol, recurso_tipo, recurso_id, detalle,
                                                    ocurrido_en) AS recalculado
                            FROM auditoria.registros),
                        seg AS (
                            SELECT id, hash_anterior_inicial, secuencia_desde,
                                   lag(hash_final) OVER (ORDER BY secuencia_desde) AS previo,
                                   lag(secuencia_hasta) OVER (ORDER BY secuencia_desde) AS hasta_previo
                            FROM auditoria.segmentos)
                        SELECT (SELECT count(*) FROM cadena) AS en_base,
                               (SELECT min(secuencia) FROM cadena
                                WHERE hash <> recalculado OR hash_anterior IS DISTINCT FROM anterior_real
                                   OR secuencia <= coalesce((SELECT secuencia_hasta FROM ancla), 0)) AS primera_rota,
                               (SELECT coalesce(sum(registros), 0) FROM auditoria.segmentos) AS archivados,
                               (SELECT count(*) FROM auditoria.segmentos) AS segmentos,
                               (SELECT min(id) FROM seg
                                WHERE hash_anterior_inicial IS DISTINCT FROM previo
                                   OR secuencia_desde <= coalesce(hasta_previo, 0)) AS segmento_roto,
                               coalesce((SELECT hash FROM auditoria.registros ORDER BY secuencia DESC LIMIT 1),
                                        (SELECT hash_final FROM ancla)) AS ultimo
                        """)
                .query((rs, n) -> {
                    long enBase = rs.getLong("en_base");
                    long archivados = rs.getLong("archivados");
                    Long rota = (Long) rs.getObject("primera_rota");
                    Long segmentoRoto = (Long) rs.getObject("segmento_roto");
                    return new Verificacion(enBase + archivados, rota == null && segmentoRoto == null, rota,
                            rs.getString("ultimo"), enBase, archivados, rs.getInt("segmentos"), segmentoRoto);
                })
                .single();
    }

    // -------------------------------------------------------------- archivado (ADR-29)

    private static final String SEGMENTO = """
            SELECT id, secuencia_desde, secuencia_hasta, registros, hash_anterior_inicial, hash_final, ocurrido_desde,
                   ocurrido_hasta, clave_objeto, sha256_archivo, bytes, archivado_en
            FROM auditoria.segmentos""";

    private static Segmento segmento(java.sql.ResultSet rs, int fila) throws java.sql.SQLException {
        return new Segmento(rs.getLong("id"), rs.getLong("secuencia_desde"), rs.getLong("secuencia_hasta"),
                rs.getInt("registros"), rs.getString("hash_anterior_inicial"), rs.getString("hash_final"),
                rs.getTimestamp("ocurrido_desde").toInstant(), rs.getTimestamp("ocurrido_hasta").toInstant(),
                rs.getString("clave_objeto"), rs.getString("sha256_archivo"), rs.getLong("bytes"),
                rs.getTimestamp("archivado_en").toInstant());
    }

    @Override
    public Optional<Segmento> ultimoSegmento() {
        return jdbc.sql(SEGMENTO + " ORDER BY secuencia_hasta DESC LIMIT 1").query(RepositorioAuditoriaJdbc::segmento)
                .optional();
    }

    @Override
    public List<Segmento> segmentos() {
        return jdbc.sql(SEGMENTO + " ORDER BY secuencia_desde").query(RepositorioAuditoriaJdbc::segmento).list();
    }

    @Override
    public Optional<Rango> proximoRango(Instant limite, int maximo) {
        return jdbc.sql("""
                        WITH u AS (SELECT coalesce(max(secuencia_hasta), 0) AS hasta FROM auditoria.segmentos),
                        p AS (SELECT r.secuencia, r.registrado_en FROM auditoria.registros r, u
                              WHERE r.secuencia > u.hasta ORDER BY r.secuencia LIMIT 1),
                        corte AS (SELECT p.secuencia AS desde, p.registrado_en,
                                         least(date_trunc('month', p.registrado_en, 'UTC') + interval '1 month',
                                               CAST(:limite AS timestamptz)) AS fin
                                  FROM p)
                        SELECT corte.desde,
                               (SELECT max(x.secuencia) FROM (
                                    SELECT r.secuencia FROM auditoria.registros r, u
                                    WHERE r.secuencia > u.hasta AND r.registrado_en < corte.fin
                                    ORDER BY r.secuencia LIMIT :maximo) x) AS hasta
                        FROM corte
                        WHERE corte.registrado_en < corte.fin
                        """)
                .param("limite", Timestamp.from(limite)).param("maximo", maximo)
                .query((rs, n) -> new Rango(rs.getLong("desde"), rs.getLong("hasta")))
                .optional();
    }

    @Override
    public List<RegistroArchivado> leer(Rango rango) {
        return jdbc.sql("""
                        SELECT secuencia, id_evento::text AS id_evento, tipo, version, origen,
                               correlation_id::text AS correlation_id, categoria, accion, resultado,
                               actor_id::text AS actor_id, actor_rol, recurso_tipo, recurso_id, detalle::text AS detalle,
                               to_char(ocurrido_en AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS ocurrido,
                               to_char(registrado_en AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS registrado,
                               hash_anterior, hash
                        FROM auditoria.registros WHERE secuencia BETWEEN :desde AND :hasta ORDER BY secuencia
                        """)
                .param("desde", rango.desde()).param("hasta", rango.hasta())
                .query((rs, n) -> new RegistroArchivado(rs.getLong("secuencia"), rs.getString("id_evento"),
                        rs.getString("tipo"), rs.getInt("version"), rs.getString("origen"),
                        rs.getString("correlation_id"), rs.getString("categoria"), rs.getString("accion"),
                        rs.getString("resultado"), rs.getString("actor_id"), rs.getString("actor_rol"),
                        rs.getString("recurso_tipo"), rs.getString("recurso_id"), rs.getString("detalle"),
                        rs.getString("ocurrido"), rs.getString("registrado"), rs.getString("hash_anterior"),
                        rs.getString("hash")))
                .list();
    }

    @Override
    public Long primeraRota(List<RegistroArchivado> r, String anteriorEsperado) {
        if (r.isEmpty()) {
            return null;
        }
        return jdbc.sql("""
                        WITH f AS (
                            SELECT * FROM unnest(CAST(:sec AS bigint[]), CAST(:id AS text[]), CAST(:tipo AS text[]),
                                                 CAST(:origen AS text[]), CAST(:cat AS text[]), CAST(:acc AS text[]),
                                                 CAST(:res AS text[]), CAST(:actor AS text[]), CAST(:rol AS text[]),
                                                 CAST(:rtipo AS text[]), CAST(:rid AS text[]), CAST(:det AS text[]),
                                                 CAST(:ocu AS text[]), CAST(:ant AS text[]), CAST(:hash AS text[]))
                                AS t(secuencia, id_evento, tipo, origen, categoria, accion, resultado, actor_id,
                                     actor_rol, recurso_tipo, recurso_id, detalle, ocurrido_en, hash_anterior, hash)),
                        c AS (
                            SELECT secuencia, hash, hash_anterior,
                                   CASE WHEN row_number() OVER (ORDER BY secuencia) = 1 THEN CAST(:inicio AS text)
                                        ELSE lag(hash) OVER (ORDER BY secuencia) END AS anterior_real,
                                   auditoria.huella(hash_anterior, id_evento::uuid, tipo, origen, categoria, accion,
                                                    resultado, actor_id::uuid, actor_rol, recurso_tipo, recurso_id,
                                                    detalle::jsonb, ocurrido_en::timestamptz) AS recalculado
                            FROM f)
                        SELECT min(secuencia) FROM c
                        WHERE hash IS DISTINCT FROM recalculado OR hash_anterior IS DISTINCT FROM anterior_real
                        """)
                .param("sec", r.stream().map(RegistroArchivado::secuencia).toArray(Long[]::new))
                .param("id", r.stream().map(RegistroArchivado::idEvento).toArray(String[]::new))
                .param("tipo", r.stream().map(RegistroArchivado::tipo).toArray(String[]::new))
                .param("origen", r.stream().map(RegistroArchivado::origen).toArray(String[]::new))
                .param("cat", r.stream().map(RegistroArchivado::categoria).toArray(String[]::new))
                .param("acc", r.stream().map(RegistroArchivado::accion).toArray(String[]::new))
                .param("res", r.stream().map(RegistroArchivado::resultado).toArray(String[]::new))
                .param("actor", r.stream().map(RegistroArchivado::actorId).toArray(String[]::new))
                .param("rol", r.stream().map(RegistroArchivado::actorRol).toArray(String[]::new))
                .param("rtipo", r.stream().map(RegistroArchivado::recursoTipo).toArray(String[]::new))
                .param("rid", r.stream().map(RegistroArchivado::recursoId).toArray(String[]::new))
                .param("det", r.stream().map(RegistroArchivado::detalle).toArray(String[]::new))
                .param("ocu", r.stream().map(RegistroArchivado::ocurridoEn).toArray(String[]::new))
                .param("ant", r.stream().map(RegistroArchivado::hashAnterior).toArray(String[]::new))
                .param("hash", r.stream().map(RegistroArchivado::hash).toArray(String[]::new))
                .param("inicio", anteriorEsperado)
                // null (ninguna rota) es un resultado válido: single() no lo admite.
                .query((rs, n) -> (Long) rs.getObject(1))
                .list().getFirst();
    }

    @Override
    public int archivar(Segmento s) {
        jdbc.sql("""
                        INSERT INTO auditoria.segmentos (secuencia_desde, secuencia_hasta, registros, hash_anterior_inicial,
                            hash_final, ocurrido_desde, ocurrido_hasta, clave_objeto, sha256_archivo, bytes)
                        VALUES (:desde, :hasta, :registros, :anterior, :final, :ocurridoDesde, :ocurridoHasta, :clave,
                                :sha, :bytes)
                        """)
                .param("desde", s.secuenciaDesde()).param("hasta", s.secuenciaHasta()).param("registros", s.registros())
                .param("anterior", s.hashAnteriorInicial()).param("final", s.hashFinal())
                .param("ocurridoDesde", Timestamp.from(s.ocurridoDesde()))
                .param("ocurridoHasta", Timestamp.from(s.ocurridoHasta()))
                .param("clave", s.claveObjeto()).param("sha", s.sha256Archivo()).param("bytes", s.bytes())
                .update();
        // El trigger solo deja borrar con esta marca y si un segmento ya cubre la fila.
        jdbc.sql("SELECT set_config('servired.archivando_auditoria', 'si', true)").query(String.class).single();
        return jdbc.sql("DELETE FROM auditoria.registros WHERE secuencia BETWEEN :desde AND :hasta")
                .param("desde", s.secuenciaDesde()).param("hasta", s.secuenciaHasta())
                .update();
    }

    // ------------------------------------------------------------------ anclas (ADR-31)

    @Override
    public Optional<Map.Entry<Long, String>> ultimoRegistro() {
        return jdbc.sql("""
                        SELECT secuencia, hash FROM auditoria.registros
                        UNION ALL
                        SELECT secuencia_hasta, hash_final FROM auditoria.segmentos
                        ORDER BY secuencia DESC LIMIT 1
                        """)
                .query((rs, n) -> Map.entry(rs.getLong("secuencia"), rs.getString("hash")))
                .optional();
    }

    @Override
    public Optional<String> hashEnBase(long secuencia) {
        return jdbc.sql("SELECT hash FROM auditoria.registros WHERE secuencia = :s").param("s", secuencia)
                .query(String.class).optional();
    }

    @Override
    public Optional<Segmento> segmentoQueCubre(long secuencia) {
        return jdbc.sql(SEGMENTO + " WHERE :s BETWEEN secuencia_desde AND secuencia_hasta").param("s", secuencia)
                .query(RepositorioAuditoriaJdbc::segmento).optional();
    }

    private static final String ANCLA = """
            SELECT fecha, secuencia, hash, registros, segmentos, creada_en, publicada_en, destino, referencia
            FROM auditoria.anclas""";

    private static Ancla ancla(java.sql.ResultSet rs, int fila) throws java.sql.SQLException {
        var publicada = rs.getTimestamp("publicada_en");
        return new Ancla(rs.getObject("fecha", LocalDate.class), rs.getLong("secuencia"), rs.getString("hash"),
                rs.getLong("registros"), rs.getInt("segmentos"), rs.getTimestamp("creada_en").toInstant(),
                publicada == null ? null : publicada.toInstant(), rs.getString("destino"), rs.getString("referencia"));
    }

    @Override
    public Optional<Ancla> ancla(LocalDate fecha) {
        return jdbc.sql(ANCLA + " WHERE fecha = :f").param("f", fecha).query(RepositorioAuditoriaJdbc::ancla).optional();
    }

    @Override
    public List<Ancla> anclas() {
        return jdbc.sql(ANCLA + " ORDER BY fecha").query(RepositorioAuditoriaJdbc::ancla).list();
    }

    @Override
    public List<co.edu.javeriana.servired.persistencia.dominio.Auditoria.Reencadenamiento> reencadenamientos() {
        return jdbc.sql("""
                        SELECT desde_secuencia, registros_borrados, motivo, migracion, realizado_en
                        FROM auditoria.reencadenamientos ORDER BY id""")
                .query((rs, n) -> new co.edu.javeriana.servired.persistencia.dominio.Auditoria.Reencadenamiento(
                        rs.getLong("desde_secuencia"), rs.getInt("registros_borrados"), rs.getString("motivo"),
                        rs.getString("migracion"), rs.getTimestamp("realizado_en").toInstant()))
                .list();
    }

    @Override
    public void insertarAncla(Ancla a) {
        jdbc.sql("""
                        INSERT INTO auditoria.anclas (fecha, secuencia, hash, registros, segmentos)
                        VALUES (:f, :s, :h, :r, :g) ON CONFLICT (fecha) DO NOTHING
                        """)
                .param("f", a.fecha()).param("s", a.secuencia()).param("h", a.hash()).param("r", a.registros())
                .param("g", a.segmentos()).update();
    }

    @Override
    public void marcarPublicada(LocalDate fecha, Instant publicadaEn, String destino, String referencia) {
        jdbc.sql("UPDATE auditoria.anclas SET publicada_en = :p, destino = :d, referencia = :r "
                        + "WHERE fecha = :f AND publicada_en IS NULL")
                .param("p", Timestamp.from(publicadaEn)).param("d", destino).param("r", referencia).param("f", fecha)
                .update();
    }
}
