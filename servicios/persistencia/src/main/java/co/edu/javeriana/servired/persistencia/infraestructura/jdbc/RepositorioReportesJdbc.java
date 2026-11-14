package co.edu.javeriana.servired.persistencia.infraestructura.jdbc;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioReportes;
import co.edu.javeriana.servired.persistencia.dominio.ReporteGuardado;
import co.edu.javeriana.servired.persistencia.dominio.ReporteGuardado.EntradaHistoria;
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
                        WHERE id_reporte = :id ORDER BY fecha, id
                        """)
                .param("id", idReporte)
                .query((rs, n) -> new EntradaHistoria(rs.getString("estado"),
                        instante(rs, "fecha"), rs.getString("detalle")))
                .list();
        return jdbc.sql("""
                        SELECT r.*, m.nombre AS nombre_municipio
                        FROM reportes.reportes r
                        JOIN catalogo.municipios m ON m.codigo = r.codigo_municipio
                        WHERE r.id = :id
                        """)
                .param("id", idReporte)
                .query((rs, n) -> new ReporteGuardado(
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
                        historia))
                .optional();
    }

    static Instant instante(ResultSet rs, String columna) throws SQLException {
        return rs.getTimestamp(columna).toInstant();
    }
}
