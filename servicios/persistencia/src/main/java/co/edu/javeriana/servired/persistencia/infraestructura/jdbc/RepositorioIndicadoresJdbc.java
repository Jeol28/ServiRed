package co.edu.javeriana.servired.persistencia.infraestructura.jdbc;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioIndicadores;
import co.edu.javeriana.servired.persistencia.dominio.Indicadores.ActualizacionVista;
import co.edu.javeriana.servired.persistencia.dominio.Indicadores.Agregado;
import co.edu.javeriana.servired.persistencia.dominio.Indicadores.Agrupacion;
import co.edu.javeriana.servired.persistencia.dominio.Indicadores.EstadoVista;
import co.edu.javeriana.servired.persistencia.dominio.Indicadores.Filtro;
import co.edu.javeriana.servired.persistencia.dominio.Indicadores.Hecho;
import co.edu.javeriana.servired.persistencia.dominio.Indicadores.PaginaHechos;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class RepositorioIndicadoresJdbc implements RepositorioIndicadores {

    private final JdbcClient jdbc;

    public RepositorioIndicadoresJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Upsert tolerante al desorden: los datos de creación y de restablecimiento se
     * completan una sola vez (COALESCE); la validación y el estado del caso se
     * reemplazan solo si el evento es más reciente que el ya aplicado.
     */
    @Override
    public void actualizar(ActualizacionVista a) {
        boolean traeValidacion = a.validado() != null;
        boolean traeCaso = a.estadoCaso() != null || a.prioridad() != null || a.idEmpresa() != null;
        jdbc.sql("""
                        INSERT INTO indicadores.vista_reportes AS v
                            (id_reporte, tipo_servicio, codigo_municipio, latitud, longitud, magnitud, inicio_estimado,
                             fecha_registro, fecha_restablecimiento, duracion_minutos, validado, confirmaciones_netas,
                             validacion_en, estado_caso, prioridad, id_empresa, caso_en, resuelto_en, cerrado_en,
                             en_cabecera)
                        VALUES (:id, :servicio, :municipio, :lat, :lon, :magnitud, :inicio, :registro, :restablecido,
                                :duracion, COALESCE(:validado, false), COALESCE(:netas, 0), :validacionEn,
                                :estadoCaso, :prioridad, :empresa, :casoEn, :resuelto, :cerrado,
                                CASE WHEN CAST(:lat AS float8) IS NOT NULL AND CAST(:lon AS float8) IS NOT NULL
                                     THEN catalogo.en_cabecera(:lat, :lon) END)
                        ON CONFLICT (id_reporte) DO UPDATE SET
                            tipo_servicio = COALESCE(v.tipo_servicio, EXCLUDED.tipo_servicio),
                            codigo_municipio = COALESCE(v.codigo_municipio, EXCLUDED.codigo_municipio),
                            latitud = COALESCE(v.latitud, EXCLUDED.latitud),
                            longitud = COALESCE(v.longitud, EXCLUDED.longitud),
                            en_cabecera = COALESCE(v.en_cabecera, EXCLUDED.en_cabecera),
                            magnitud = COALESCE(v.magnitud, EXCLUDED.magnitud),
                            inicio_estimado = COALESCE(v.inicio_estimado, EXCLUDED.inicio_estimado),
                            fecha_registro = COALESCE(v.fecha_registro, EXCLUDED.fecha_registro),
                            fecha_restablecimiento = COALESCE(v.fecha_restablecimiento, EXCLUDED.fecha_restablecimiento),
                            duracion_minutos = COALESCE(v.duracion_minutos, EXCLUDED.duracion_minutos),
                            validado = CASE WHEN EXCLUDED.validacion_en IS NOT NULL
                                             AND (v.validacion_en IS NULL OR EXCLUDED.validacion_en >= v.validacion_en)
                                            THEN EXCLUDED.validado ELSE v.validado END,
                            confirmaciones_netas = CASE WHEN EXCLUDED.validacion_en IS NOT NULL
                                             AND (v.validacion_en IS NULL OR EXCLUDED.validacion_en >= v.validacion_en)
                                            THEN EXCLUDED.confirmaciones_netas ELSE v.confirmaciones_netas END,
                            validacion_en = GREATEST(v.validacion_en, EXCLUDED.validacion_en),
                            estado_caso = CASE WHEN EXCLUDED.caso_en IS NOT NULL AND EXCLUDED.estado_caso IS NOT NULL
                                             AND (v.caso_en IS NULL OR EXCLUDED.caso_en >= v.caso_en)
                                            THEN EXCLUDED.estado_caso ELSE v.estado_caso END,
                            prioridad = CASE WHEN EXCLUDED.caso_en IS NOT NULL AND EXCLUDED.prioridad IS NOT NULL
                                             AND (v.caso_en IS NULL OR EXCLUDED.caso_en >= v.caso_en)
                                            THEN EXCLUDED.prioridad ELSE v.prioridad END,
                            id_empresa = CASE WHEN EXCLUDED.caso_en IS NOT NULL AND EXCLUDED.id_empresa IS NOT NULL
                                             AND (v.caso_en IS NULL OR EXCLUDED.caso_en >= v.caso_en)
                                            THEN EXCLUDED.id_empresa ELSE v.id_empresa END,
                            caso_en = GREATEST(v.caso_en, EXCLUDED.caso_en),
                            resuelto_en = COALESCE(v.resuelto_en, EXCLUDED.resuelto_en),
                            cerrado_en = COALESCE(v.cerrado_en, EXCLUDED.cerrado_en),
                            actualizado_en = now()
                        """)
                .param("id", a.idReporte()).param("servicio", a.tipoServicio()).param("municipio", a.codigoMunicipio())
                .param("lat", a.latitud()).param("lon", a.longitud()).param("magnitud", a.magnitud())
                .param("inicio", ts(a.inicioEstimado())).param("registro", ts(a.fechaRegistro()))
                .param("restablecido", ts(a.fechaRestablecimiento())).param("duracion", a.duracionMinutos())
                .param("validado", a.validado()).param("netas", a.confirmacionesNetas())
                .param("validacionEn", traeValidacion ? ts(a.fechaEvento()) : null)
                .param("estadoCaso", a.estadoCaso()).param("prioridad", a.prioridad()).param("empresa", a.idEmpresa())
                .param("casoEn", traeCaso ? ts(a.fechaEvento()) : null)
                .param("resuelto", ts(a.resueltoEn())).param("cerrado", ts(a.cerradoEn()))
                .update();
    }

    /** Columnas comparables: las de negocio (caso_en y validacion_en solo ordenan eventos). */
    private static final java.util.List<String> COMPARABLES = java.util.List.of(
            "tipo_servicio", "codigo_municipio", "latitud", "longitud", "magnitud", "inicio_estimado",
            "fecha_registro", "fecha_restablecimiento", "duracion_minutos", "validado", "confirmaciones_netas",
            "estado_caso", "prioridad", "id_empresa", "resuelto_en", "cerrado_en", "en_cabecera");
    private static final java.util.Set<String> FECHAS = java.util.Set.of(
            "inicio_estimado", "fecha_registro", "fecha_restablecimiento", "resuelto_en", "cerrado_en");

    /**
     * La fila que la vista debería tener para cada reporte, calculada desde el origen: el
     * reporte y su restablecimiento, los votos de CORTE que cuentan (ADR-22) y el caso.
     */
    private static final String VERDAD = """
            WITH votos AS (
                SELECT id_reporte,
                       count(*) FILTER (WHERE sentido = 'CONFIRMA') - count(*) FILTER (WHERE sentido = 'CONTROVIERTE') AS netas,
                       max(fecha) AS ultima
                FROM reportes.validaciones WHERE objeto = 'CORTE' AND cuenta GROUP BY id_reporte),
            casos AS (
                SELECT c.id_reporte, c.estado, c.prioridad, c.id_empresa, c.resuelto_en, c.cerrado_en,
                       GREATEST(c.abierto_en, (SELECT max(h.fecha) FROM atencion.historia_caso h WHERE h.id_caso = c.id)) AS caso_en
                FROM atencion.casos c),
            verdad AS (
                SELECT r.id AS id_reporte, r.tipo_servicio, r.codigo_municipio, r.latitud, r.longitud, r.magnitud,
                       r.inicio_estimado, r.fecha_registro, r.fecha_restablecimiento, r.duracion_minutos,
                       COALESCE(v.netas, 0) >= :umbral AS validado, COALESCE(v.netas, 0)::int AS confirmaciones_netas,
                       v.ultima AS validacion_en, c.estado AS estado_caso, c.prioridad, c.id_empresa, c.caso_en,
                       c.resuelto_en, c.cerrado_en, catalogo.en_cabecera(r.latitud, r.longitud) AS en_cabecera
                FROM reportes.reportes r
                LEFT JOIN votos v ON v.id_reporte = r.id
                LEFT JOIN casos c ON c.id_reporte = r.id)
            """;

    @Override
    public co.edu.javeriana.servired.persistencia.dominio.Indicadores.Reconstruccion reconstruir(int umbral,
                                                                                                 boolean aplicar) {
        // Mientras se compara y reescribe, la proyección de eventos espera (no escribe a medias).
        jdbc.sql("LOCK TABLE indicadores.vista_reportes IN SHARE ROW EXCLUSIVE MODE").update();
        var porColumna = new java.util.LinkedHashMap<String, Long>();
        var conteos = new StringBuilder();
        for (String c : COMPARABLES) {
            String a = FECHAS.contains(c) ? "date_trunc('milliseconds', t." + c + ")" : "t." + c;
            String b = FECHAS.contains(c) ? "date_trunc('milliseconds', x." + c + ")" : "x." + c;
            conteos.append(", count(*) FILTER (WHERE ").append(a).append(" IS DISTINCT FROM ").append(b)
                    .append(") AS ").append(c);
        }
        String distinta = COMPARABLES.stream()
                .map(c -> FECHAS.contains(c)
                        ? "date_trunc('milliseconds', t." + c + ") IS DISTINCT FROM date_trunc('milliseconds', x." + c + ")"
                        : "t." + c + " IS DISTINCT FROM x." + c)
                .collect(java.util.stream.Collectors.joining(" OR "));
        var fila = jdbc.sql(VERDAD + """
                        SELECT (SELECT count(*) FROM verdad) AS origen,
                               (SELECT count(*) FROM indicadores.vista_reportes) AS vista,
                               (SELECT count(*) FROM verdad t WHERE NOT EXISTS
                                   (SELECT 1 FROM indicadores.vista_reportes x WHERE x.id_reporte = t.id_reporte)) AS faltantes,
                               (SELECT count(*) FROM indicadores.vista_reportes x WHERE NOT EXISTS
                                   (SELECT 1 FROM verdad t WHERE t.id_reporte = x.id_reporte)) AS sobrantes,
                               d.*
                        FROM (SELECT count(*) FILTER (WHERE %s) AS distintas %s
                              FROM verdad t JOIN indicadores.vista_reportes x ON x.id_reporte = t.id_reporte) d
                        """.formatted(distinta, conteos))
                .param("umbral", umbral)
                .query((rs, n) -> {
                    for (String c : COMPARABLES) {
                        long v = rs.getLong(c);
                        if (v > 0) {
                            porColumna.put(c, v);
                        }
                    }
                    return new long[]{rs.getLong("origen"), rs.getLong("vista"), rs.getLong("faltantes"),
                            rs.getLong("sobrantes"), rs.getLong("distintas")};
                })
                .single();
        if (aplicar) {
            String columnas = "id_reporte, " + String.join(", ", COMPARABLES) + ", validacion_en, caso_en";
            String asignaciones = java.util.stream.Stream.concat(COMPARABLES.stream(),
                            java.util.stream.Stream.of("validacion_en", "caso_en"))
                    .map(c -> c + " = EXCLUDED." + c)
                    .collect(java.util.stream.Collectors.joining(", "));
            jdbc.sql(VERDAD + "INSERT INTO indicadores.vista_reportes (" + columnas + ", actualizado_en) "
                            + "SELECT " + columnas + ", now() FROM verdad "
                            + "ON CONFLICT (id_reporte) DO UPDATE SET " + asignaciones + ", actualizado_en = now()")
                    .param("umbral", umbral)
                    .update();
            jdbc.sql("DELETE FROM indicadores.vista_reportes x "
                    + "WHERE NOT EXISTS (SELECT 1 FROM reportes.reportes r WHERE r.id = x.id_reporte)").update();
        }
        return new co.edu.javeriana.servired.persistencia.dominio.Indicadores.Reconstruccion(
                fila[0], fila[1], fila[2], fila[3], fila[4], porColumna, aplicar);
    }

    private static final String DESDE = """
             FROM indicadores.vista_reportes v
             JOIN catalogo.municipios m ON m.codigo = v.codigo_municipio
             JOIN catalogo.departamentos d ON d.codigo = m.codigo_departamento
            WHERE v.fecha_registro >= :desde AND v.fecha_registro < :hasta
              AND (CAST(:servicio AS text) IS NULL OR v.tipo_servicio = :servicio)
              AND (CAST(:departamento AS text) IS NULL OR d.codigo = :departamento)
              AND (CAST(:municipio AS text) IS NULL OR v.codigo_municipio = :municipio)
              AND (CAST(:empresa AS uuid) IS NULL OR v.id_empresa = :empresa)
              AND (CAST(:situacion AS text) IS NULL
                   OR (:situacion = 'ACTIVO' AND v.fecha_restablecimiento IS NULL
                       AND COALESCE(v.estado_caso, 'RECIBIDO') NOT IN ('RESUELTO', 'CERRADO'))
                   OR (:situacion = 'RESUELTO' AND (v.fecha_restablecimiento IS NOT NULL
                       OR v.estado_caso IN ('RESUELTO', 'CERRADO'))))
            """;

    @Override
    public List<Agregado> agregar(Filtro f, Agrupacion agrupacion, double celda, double celdaRural) {
        // Celdas (ADR-28): en una cabecera municipal miden :celda; fuera, :celdaRural. Sin
        // clasificación (null) se trata como rural, que es lo más protector.
        String lado = "(CASE WHEN v.en_cabecera THEN CAST(:celda AS float8) ELSE CAST(:celdaRural AS float8) END)";
        String claves = switch (agrupacion) {
            case NINGUNA -> "NULL::text AS codigo, NULL::text AS nombre, NULL::text AS departamento, NULL::text AS servicio, "
                    + "NULL::timestamptz AS mes, NULL::float8 AS clat, NULL::float8 AS clon, NULL::boolean AS cabecera";
            case SERVICIO -> "v.tipo_servicio AS codigo, v.tipo_servicio AS nombre, NULL::text AS departamento, "
                    + "v.tipo_servicio AS servicio, NULL::timestamptz AS mes, NULL::float8 AS clat, NULL::float8 AS clon, NULL::boolean AS cabecera";
            case DEPARTAMENTO -> "d.codigo::text AS codigo, d.nombre AS nombre, d.nombre AS departamento, NULL::text AS servicio, "
                    + "NULL::timestamptz AS mes, NULL::float8 AS clat, NULL::float8 AS clon, NULL::boolean AS cabecera";
            case MUNICIPIO -> "m.codigo::text AS codigo, m.nombre AS nombre, d.nombre AS departamento, NULL::text AS servicio, "
                    + "NULL::timestamptz AS mes, NULL::float8 AS clat, NULL::float8 AS clon, NULL::boolean AS cabecera";
            case MUNICIPIO_SERVICIO_MES -> "m.codigo::text AS codigo, m.nombre AS nombre, d.nombre AS departamento, "
                    + "v.tipo_servicio AS servicio, date_trunc('month', v.fecha_registro, 'UTC') AS mes, "
                    + "NULL::float8 AS clat, NULL::float8 AS clon, NULL::boolean AS cabecera";
            case CELDA -> "NULL::text AS codigo, NULL::text AS nombre, NULL::text AS departamento, NULL::text AS servicio, "
                    + "NULL::timestamptz AS mes, (floor(v.latitud / " + lado + ") + 0.5) * " + lado + " AS clat, "
                    + "(floor(v.longitud / " + lado + ") + 0.5) * " + lado + " AS clon, "
                    + "COALESCE(v.en_cabecera, false) AS cabecera";
        };
        String agrupar = switch (agrupacion) {
            case NINGUNA -> "";
            case CELDA -> " GROUP BY 6, 7, 8";
            default -> " GROUP BY 1, 2, 3, 4, 5";
        };
        return parametros(jdbc.sql("SELECT " + claves + """
                        , count(*) AS reportes,
                          count(v.duracion_minutos) AS con_duracion,
                          avg(v.duracion_minutos) AS duracion_promedio,
                          percentile_cont(0.5) WITHIN GROUP (ORDER BY v.duracion_minutos) AS duracion_mediana,
                          percentile_cont(0.9) WITHIN GROUP (ORDER BY v.duracion_minutos) AS duracion_p90,
                          count(*) FILTER (WHERE v.magnitud = 'VIVIENDA') AS vivienda,
                          count(*) FILTER (WHERE v.magnitud = 'CUADRA') AS cuadra,
                          count(*) FILTER (WHERE v.magnitud = 'BARRIO') AS barrio,
                          count(*) FILTER (WHERE v.magnitud = 'SECTOR') AS sector,
                          count(*) FILTER (WHERE v.validado) AS validados,
                          count(*) FILTER (WHERE v.fecha_restablecimiento IS NOT NULL
                                            OR v.estado_caso IN ('RESUELTO', 'CERRADO')) AS resueltos,
                          avg(extract(epoch FROM (v.resuelto_en - v.fecha_registro)) / 60)
                              FILTER (WHERE v.resuelto_en IS NOT NULL) AS tiempo_resolucion
                        """ + DESDE + agrupar + " ORDER BY reportes DESC"), f)
                .param("celda", celda)
                .param("celdaRural", celdaRural)
                .query((rs, n) -> new Agregado(rs.getString("codigo"), rs.getString("nombre"),
                        rs.getString("departamento"), rs.getString("servicio"), instante(rs, "mes"),
                        decimal(rs, "clat"), decimal(rs, "clon"), (Boolean) rs.getObject("cabecera"),
                        rs.getLong("reportes"), rs.getLong("con_duracion"),
                        decimal(rs, "duracion_promedio"), decimal(rs, "duracion_mediana"), decimal(rs, "duracion_p90"),
                        rs.getLong("vivienda"), rs.getLong("cuadra"), rs.getLong("barrio"), rs.getLong("sector"),
                        rs.getLong("validados"), rs.getLong("resueltos"), decimal(rs, "tiempo_resolucion")))
                .list()
                .stream().filter(a -> a.reportes() > 0).toList();
    }

    @Override
    public PaginaHechos hechos(Filtro f, int desde, int limite) {
        var filas = parametros(jdbc.sql("""
                        SELECT v.*, m.nombre AS nombre_municipio, d.nombre AS departamento
                        """ + DESDE + " AND v.latitud IS NOT NULL ORDER BY v.fecha_registro, v.id_reporte LIMIT :limite OFFSET :offset"), f)
                .param("limite", limite + 1).param("offset", desde)
                .query((rs, n) -> new Hecho(rs.getObject("id_reporte", UUID.class), rs.getString("tipo_servicio"),
                        rs.getString("codigo_municipio"), rs.getString("nombre_municipio"), rs.getString("departamento"),
                        rs.getDouble("latitud"), rs.getDouble("longitud"), rs.getString("magnitud"),
                        instante(rs, "inicio_estimado"), instante(rs, "fecha_registro"),
                        instante(rs, "fecha_restablecimiento"), (Integer) rs.getObject("duracion_minutos"),
                        rs.getBoolean("validado"), rs.getInt("confirmaciones_netas"), rs.getString("estado_caso"),
                        rs.getString("prioridad"), rs.getObject("id_empresa", UUID.class), instante(rs, "resuelto_en"),
                        instante(rs, "cerrado_en")))
                .list();
        boolean hayMas = filas.size() > limite;
        return new PaginaHechos(hayMas ? filas.subList(0, limite) : filas, hayMas);
    }

    @Override
    public EstadoVista estado() {
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE fecha_registro IS NOT NULL) AS reportes,
                               count(DISTINCT codigo_municipio) AS municipios,
                               min(fecha_registro) AS primero, max(fecha_registro) AS ultimo,
                               max(actualizado_en) AS actualizado
                        FROM indicadores.vista_reportes
                        """)
                .query((rs, n) -> new EstadoVista(rs.getLong("reportes"), rs.getLong("municipios"),
                        instante(rs, "primero"), instante(rs, "ultimo"), instante(rs, "actualizado")))
                .single();
    }

    private static JdbcClient.StatementSpec parametros(JdbcClient.StatementSpec sql, Filtro f) {
        return sql.param("desde", Timestamp.from(f.desde())).param("hasta", Timestamp.from(f.hasta()))
                .param("servicio", f.tipoServicio()).param("departamento", f.codigoDepartamento())
                .param("municipio", f.codigoMunicipio()).param("empresa", f.idEmpresa())
                .param("situacion", f.situacion());
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instante(ResultSet rs, String columna) throws SQLException {
        Timestamp t = rs.getTimestamp(columna);
        return t == null ? null : t.toInstant();
    }

    private static Double decimal(ResultSet rs, String columna) throws SQLException {
        double d = rs.getDouble(columna);
        return rs.wasNull() ? null : d;
    }
}
