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
                             validacion_en, estado_caso, prioridad, id_empresa, caso_en, resuelto_en, cerrado_en)
                        VALUES (:id, :servicio, :municipio, :lat, :lon, :magnitud, :inicio, :registro, :restablecido,
                                :duracion, COALESCE(:validado, false), COALESCE(:netas, 0), :validacionEn,
                                :estadoCaso, :prioridad, :empresa, :casoEn, :resuelto, :cerrado)
                        ON CONFLICT (id_reporte) DO UPDATE SET
                            tipo_servicio = COALESCE(v.tipo_servicio, EXCLUDED.tipo_servicio),
                            codigo_municipio = COALESCE(v.codigo_municipio, EXCLUDED.codigo_municipio),
                            latitud = COALESCE(v.latitud, EXCLUDED.latitud),
                            longitud = COALESCE(v.longitud, EXCLUDED.longitud),
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
    public List<Agregado> agregar(Filtro f, Agrupacion agrupacion, double celda) {
        String claves = switch (agrupacion) {
            case NINGUNA -> "NULL::text AS codigo, NULL::text AS nombre, NULL::text AS departamento, NULL::text AS servicio, "
                    + "NULL::timestamptz AS mes, NULL::float8 AS clat, NULL::float8 AS clon";
            case SERVICIO -> "v.tipo_servicio AS codigo, v.tipo_servicio AS nombre, NULL::text AS departamento, "
                    + "v.tipo_servicio AS servicio, NULL::timestamptz AS mes, NULL::float8 AS clat, NULL::float8 AS clon";
            case DEPARTAMENTO -> "d.codigo::text AS codigo, d.nombre AS nombre, d.nombre AS departamento, NULL::text AS servicio, "
                    + "NULL::timestamptz AS mes, NULL::float8 AS clat, NULL::float8 AS clon";
            case MUNICIPIO -> "m.codigo::text AS codigo, m.nombre AS nombre, d.nombre AS departamento, NULL::text AS servicio, "
                    + "NULL::timestamptz AS mes, NULL::float8 AS clat, NULL::float8 AS clon";
            case MUNICIPIO_SERVICIO_MES -> "m.codigo::text AS codigo, m.nombre AS nombre, d.nombre AS departamento, "
                    + "v.tipo_servicio AS servicio, date_trunc('month', v.fecha_registro, 'UTC') AS mes, "
                    + "NULL::float8 AS clat, NULL::float8 AS clon";
            case CELDA -> "NULL::text AS codigo, NULL::text AS nombre, NULL::text AS departamento, NULL::text AS servicio, "
                    + "NULL::timestamptz AS mes, (floor(v.latitud / :celda) + 0.5) * :celda AS clat, "
                    + "(floor(v.longitud / :celda) + 0.5) * :celda AS clon";
        };
        String agrupar = switch (agrupacion) {
            case NINGUNA -> "";
            case CELDA -> " GROUP BY 6, 7";
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
                .query((rs, n) -> new Agregado(rs.getString("codigo"), rs.getString("nombre"),
                        rs.getString("departamento"), rs.getString("servicio"), instante(rs, "mes"),
                        decimal(rs, "clat"), decimal(rs, "clon"), rs.getLong("reportes"), rs.getLong("con_duracion"),
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
