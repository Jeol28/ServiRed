package co.edu.javeriana.servired.persistencia.infraestructura.jdbc;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioReportes;
import co.edu.javeriana.servired.persistencia.dominio.ReporteGuardado;
import co.edu.javeriana.servired.persistencia.dominio.ReporteGuardado.Conteo;
import co.edu.javeriana.servired.persistencia.dominio.ReporteGuardado.EntradaHistoria;
import co.edu.javeriana.servired.persistencia.dominio.ReporteGuardado.EvidenciaGuardada;
import co.edu.javeriana.servired.persistencia.dominio.ReporteNuevo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Esquema reportes. Solo este adaptador conoce sus tablas. */
@Component
public class RepositorioReportesJdbc implements RepositorioReportes {

    private final JdbcClient jdbc;

    public RepositorioReportesJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertar(ReporteNuevo r) {
        jdbc.sql("""
                        INSERT INTO reportes.reportes
                            (id, id_ciudadano, tipo_servicio, latitud, longitud, codigo_municipio,
                             inicio_estimado, magnitud, descripcion, estado, fecha_registro)
                        VALUES (:id, :ciudadano, :servicio, :lat, :lon, :municipio,
                                :inicio, :magnitud, :descripcion, :estado, :registro)
                        """)
                .param("id", r.idReporte())
                .param("ciudadano", r.idCiudadano())
                .param("servicio", r.tipoServicio())
                .param("lat", r.latitud())
                .param("lon", r.longitud())
                .param("municipio", r.codigoMunicipio())
                .param("inicio", Timestamp.from(r.inicioEstimado()))
                .param("magnitud", r.magnitud())
                .param("descripcion", r.descripcion())
                .param("estado", ReporteNuevo.ESTADO_INICIAL)
                .param("registro", Timestamp.from(r.fechaRegistro()))
                .update();
        jdbc.sql("""
                        INSERT INTO reportes.historia_reporte (id_reporte, estado, fecha, detalle)
                        VALUES (:id, :estado, :fecha, 'Reporte recibido')
                        """)
                .param("id", r.idReporte())
                .param("estado", ReporteNuevo.ESTADO_INICIAL)
                .param("fecha", Timestamp.from(r.fechaRegistro()))
                .update();
    }

    @Override
    public Optional<ReporteGuardado> buscar(UUID idReporte) {
        List<EntradaHistoria> historia = jdbc.sql("""
                        SELECT estado, fecha, detalle FROM reportes.historia_reporte
                        WHERE id_reporte = :id ORDER BY id
                        """)
                .param("id", idReporte)
                .query((rs, n) -> new EntradaHistoria(rs.getString("estado"),
                        instante(rs, "fecha"), rs.getString("detalle")))
                .list();
        List<EvidenciaGuardada> evidencias = jdbc.sql("""
                        SELECT id, tipo_contenido, tamano_bytes, creada_en FROM reportes.evidencias
                        WHERE id_reporte = :id ORDER BY creada_en, id
                        """)
                .param("id", idReporte)
                .query((rs, n) -> new EvidenciaGuardada(rs.getObject("id", UUID.class),
                        rs.getString("tipo_contenido"), rs.getInt("tamano_bytes"), instante(rs, "creada_en")))
                .list();
        return jdbc.sql(CONSULTA_REPORTE + " WHERE r.id = :id" + AGRUPAR)
                .param("id", idReporte)
                .query((rs, n) -> mapear(rs, historia, evidencias))
                .optional();
    }

    /** Reporte con su municipio y el conteo de validaciones por objeto. */
    static final String CONSULTA_REPORTE = """
            SELECT r.*, m.nombre AS nombre_municipio,
                   count(*) FILTER (WHERE v.objeto = 'CORTE' AND v.sentido = 'CONFIRMA') AS corte_confirma,
                   count(*) FILTER (WHERE v.objeto = 'CORTE' AND v.sentido = 'CONTROVIERTE') AS corte_controvierte,
                   count(*) FILTER (WHERE v.objeto = 'RESTABLECIMIENTO' AND v.sentido = 'CONFIRMA') AS rest_confirma,
                   count(*) FILTER (WHERE v.objeto = 'RESTABLECIMIENTO' AND v.sentido = 'CONTROVIERTE') AS rest_controvierte
            FROM reportes.reportes r
            JOIN catalogo.municipios m ON m.codigo = r.codigo_municipio
            LEFT JOIN reportes.validaciones v ON v.id_reporte = r.id
            """;

    static final String AGRUPAR = " GROUP BY r.id, m.nombre";

    static ReporteGuardado mapear(ResultSet rs, List<EntradaHistoria> historia, List<EvidenciaGuardada> evidencias)
            throws SQLException {
        Timestamp restablecido = rs.getTimestamp("fecha_restablecimiento");
        int duracion = rs.getInt("duracion_minutos");
        boolean sinDuracion = rs.wasNull();
        return new ReporteGuardado(
                rs.getObject("id", UUID.class),
                rs.getObject("id_ciudadano", UUID.class),
                rs.getString("tipo_servicio"),
                rs.getDouble("latitud"),
                rs.getDouble("longitud"),
                rs.getString("codigo_municipio"),
                rs.getString("nombre_municipio"),
                instante(rs, "inicio_estimado"),
                rs.getString("magnitud"),
                rs.getString("descripcion"),
                rs.getString("estado"),
                instante(rs, "fecha_registro"),
                restablecido == null ? null : restablecido.toInstant(),
                sinDuracion ? null : duracion,
                rs.getInt("version_validaciones"),
                new Conteo(rs.getInt("corte_confirma"), rs.getInt("corte_controvierte")),
                new Conteo(rs.getInt("rest_confirma"), rs.getInt("rest_controvierte")),
                historia,
                evidencias);
    }

    static Instant instante(ResultSet rs, String columna) throws SQLException {
        return rs.getTimestamp(columna).toInstant();
    }
}
