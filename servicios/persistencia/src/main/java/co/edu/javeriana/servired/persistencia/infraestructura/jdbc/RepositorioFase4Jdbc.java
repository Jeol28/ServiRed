package co.edu.javeriana.servired.persistencia.infraestructura.jdbc;

import static co.edu.javeriana.servired.persistencia.infraestructura.jdbc.RepositorioReportesJdbc.instante;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioFase4;
import co.edu.javeriana.servired.persistencia.dominio.Fase4.DenunciaGuardada;
import co.edu.javeriana.servired.persistencia.dominio.Fase4.DenunciaNueva;
import co.edu.javeriana.servired.persistencia.dominio.Fase4.PaginaReportes;
import co.edu.javeriana.servired.persistencia.dominio.Fase4.ReporteCercano;
import co.edu.javeriana.servired.persistencia.dominio.Fase4.Restablecimiento;
import co.edu.javeriana.servired.persistencia.dominio.Fase4.VotoNuevo;
import co.edu.javeriana.servired.persistencia.dominio.ReporteGuardado.Conteo;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class RepositorioFase4Jdbc implements RepositorioFase4 {

    private final JdbcClient jdbc;

    public RepositorioFase4Jdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean existeReporte(UUID idReporte) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM reportes.reportes WHERE id = :id)")
                .param("id", idReporte).query(Boolean.class).single();
    }

    @Override
    public boolean bloquearReporte(UUID idReporte) {
        return jdbc.sql("SELECT id FROM reportes.reportes WHERE id = :id FOR UPDATE")
                .param("id", idReporte).query(UUID.class).optional().isPresent();
    }

    @Override
    public boolean registrarRestablecimiento(UUID idReporte, Restablecimiento r) {
        return jdbc.sql("""
                        UPDATE reportes.reportes SET
                            estado = 'RESTABLECIDO',
                            fecha_restablecimiento = :fecha,
                            duracion_minutos = :duracion,
                            comentario_restablecimiento = :comentario,
                            actualizado_en = now()
                        WHERE id = :id AND (estado IN ('RECIBIDO', 'EN_ATENCION')
                              OR (estado = 'RESTABLECIDO' AND fecha_restablecimiento IS NULL))
                        """)
                .param("fecha", Timestamp.from(r.fechaRestablecimiento()))
                .param("duracion", r.duracionMinutos())
                .param("comentario", r.comentario())
                .param("id", idReporte)
                .update() == 1;
    }

    @Override
    public boolean cambiarEstado(UUID idReporte, String estadoNuevo, List<String> desde) {
        if (desde.isEmpty()) {
            return false;
        }
        return jdbc.sql("UPDATE reportes.reportes SET estado = :nuevo, actualizado_en = now() WHERE id = :id AND estado IN (:desde)")
                .param("nuevo", estadoNuevo).param("id", idReporte).param("desde", desde)
                .update() == 1;
    }

    @Override
    public boolean avanzarVersionValidaciones(UUID idReporte, int versionEsperada) {
        return jdbc.sql("""
                        UPDATE reportes.reportes SET version_validaciones = version_validaciones + 1
                        WHERE id = :id AND version_validaciones = :version
                        """)
                .param("id", idReporte)
                .param("version", versionEsperada)
                .update() == 1;
    }

    @Override
    public boolean insertarVoto(UUID idReporte, VotoNuevo v) {
        return jdbc.sql("""
                        INSERT INTO reportes.validaciones (id_reporte, objeto, id_ciudadano, sentido, fecha, cuenta)
                        VALUES (:reporte, :objeto, :ciudadano, :sentido, :fecha, :cuenta)
                        ON CONFLICT DO NOTHING
                        """)
                .param("reporte", idReporte)
                .param("objeto", v.objeto())
                .param("ciudadano", v.idCiudadano())
                .param("sentido", v.sentido())
                .param("fecha", Timestamp.from(v.fecha()))
                .param("cuenta", v.cuenta())
                .update() == 1;
    }

    @Override
    public void agregarHistoria(UUID idReporte, String estado, Instant fecha, String detalle) {
        jdbc.sql("INSERT INTO reportes.historia_reporte (id_reporte, estado, fecha, detalle) VALUES (:id, :e, :f, :d)")
                .param("id", idReporte).param("e", estado).param("f", Timestamp.from(fecha)).param("d", detalle)
                .update();
    }

    @Override
    public PaginaReportes listarDeCiudadano(UUID idCiudadano, int pagina, int tamano) {
        long total = jdbc.sql("SELECT count(*) FROM reportes.reportes WHERE id_ciudadano = :c")
                .param("c", idCiudadano).query(Long.class).single();
        var elementos = jdbc.sql(RepositorioReportesJdbc.CONSULTA_REPORTE + " WHERE r.id_ciudadano = :c"
                        + RepositorioReportesJdbc.AGRUPAR + " ORDER BY r.fecha_registro DESC, r.id LIMIT :l OFFSET :o")
                .param("c", idCiudadano)
                .param("l", tamano)
                .param("o", (long) pagina * tamano)
                .query((rs, n) -> RepositorioReportesJdbc.mapear(rs, List.of(), List.of()))
                .list();
        return new PaginaReportes(elementos, total, pagina, tamano);
    }

    /**
     * Filtro rápido por caja (índice por municipio no aplica aquí) y distancia
     * exacta con la fórmula del haversine. Excluye reportes cerrados.
     */
    @Override
    public List<ReporteCercano> cercanos(double latitud, double longitud, int radioMetros, UUID excluir, int limite) {
        double dLat = radioMetros / 111_320.0;
        double dLon = radioMetros / (111_320.0 * Math.max(0.01, Math.cos(Math.toRadians(latitud))));
        return jdbc.sql("""
                        SELECT * FROM (
                            SELECT r.id, r.tipo_servicio, r.magnitud, r.estado, r.inicio_estimado,
                                   r.latitud, r.longitud,
                                   round(2 * 6371000 * asin(sqrt(
                                       power(sin(radians(r.latitud - :lat) / 2), 2) +
                                       cos(radians(:lat)) * cos(radians(r.latitud)) *
                                       power(sin(radians(r.longitud - :lon) / 2), 2)))) AS distancia,
                                   count(*) FILTER (WHERE v.cuenta AND v.objeto = 'CORTE' AND v.sentido = 'CONFIRMA') AS cc,
                                   count(*) FILTER (WHERE v.cuenta AND v.objeto = 'CORTE' AND v.sentido = 'CONTROVIERTE') AS cv,
                                   count(*) FILTER (WHERE v.cuenta AND v.objeto = 'RESTABLECIMIENTO' AND v.sentido = 'CONFIRMA') AS rc,
                                   count(*) FILTER (WHERE v.cuenta AND v.objeto = 'RESTABLECIMIENTO' AND v.sentido = 'CONTROVIERTE') AS rv
                            FROM reportes.reportes r
                            LEFT JOIN reportes.validaciones v ON v.id_reporte = r.id
                            WHERE r.estado <> 'CERRADO'
                              AND (CAST(:excluir AS uuid) IS NULL OR r.id_ciudadano <> :excluir)
                              AND r.latitud BETWEEN :lat - :dlat AND :lat + :dlat
                              AND r.longitud BETWEEN :lon - :dlon AND :lon + :dlon
                            GROUP BY r.id
                        ) c
                        WHERE c.distancia <= :radio
                        ORDER BY c.distancia, c.id
                        LIMIT :limite
                        """)
                .param("lat", latitud).param("lon", longitud).param("dlat", dLat).param("dlon", dLon)
                .param("excluir", excluir).param("radio", radioMetros).param("limite", limite)
                .query((rs, n) -> new ReporteCercano(rs.getObject("id", UUID.class), rs.getString("tipo_servicio"),
                        rs.getString("magnitud"), rs.getString("estado"), instante(rs, "inicio_estimado"),
                        rs.getDouble("latitud"), rs.getDouble("longitud"), rs.getInt("distancia"),
                        new Conteo(rs.getInt("cc"), rs.getInt("cv")), new Conteo(rs.getInt("rc"), rs.getInt("rv"))))
                .list();
    }

    @Override
    public int contarEvidencias(UUID idReporte) {
        return jdbc.sql("SELECT count(*) FROM reportes.evidencias WHERE id_reporte = :id")
                .param("id", idReporte).query(Integer.class).single();
    }

    @Override
    public void insertarEvidencia(UUID idEvidencia, UUID idReporte, UUID idDenuncia, String clave, String tipo,
                                  int tamano, String sha256, Instant creadaEn) {
        jdbc.sql("""
                        INSERT INTO reportes.evidencias
                            (id, id_reporte, id_denuncia, clave_objeto, tipo_contenido, tamano_bytes, sha256, creada_en)
                        VALUES (:id, :reporte, :denuncia, :clave, :tipo, :tamano, :sha, :creada)
                        """)
                .param("id", idEvidencia).param("reporte", idReporte).param("denuncia", idDenuncia)
                .param("clave", clave).param("tipo", tipo).param("tamano", tamano).param("sha", sha256)
                .param("creada", Timestamp.from(creadaEn))
                .update();
    }

    @Override
    public Optional<EvidenciaReferencia> evidenciaDeReporte(UUID idReporte, UUID idEvidencia) {
        return jdbc.sql("""
                        SELECT clave_objeto, tipo_contenido FROM reportes.evidencias
                        WHERE id = :id AND id_reporte = :reporte
                        """)
                .param("id", idEvidencia).param("reporte", idReporte)
                .query((rs, n) -> new EvidenciaReferencia(rs.getString("clave_objeto"), rs.getString("tipo_contenido")))
                .optional();
    }

    @Override
    public boolean existeClaveEvidencia(String clave) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM reportes.evidencias WHERE clave_objeto = :clave)")
                .param("clave", clave).query(Boolean.class).single();
    }

    @Override
    public void insertarDenuncia(DenunciaNueva d) {
        jdbc.sql("""
                        INSERT INTO reportes.denuncias
                            (id, tipo_servicio, latitud, longitud, codigo_municipio, descripcion, fecha_recepcion)
                        VALUES (:id, :tipo, :lat, :lon, :municipio, :descripcion, :fecha)
                        """)
                .param("id", d.idDenuncia()).param("tipo", d.tipoServicio()).param("lat", d.latitud())
                .param("lon", d.longitud()).param("municipio", d.codigoMunicipio())
                .param("descripcion", d.descripcion()).param("fecha", Timestamp.from(d.fechaRecepcion()))
                .update();
    }

    @Override
    public Optional<DenunciaGuardada> buscarDenuncia(UUID idDenuncia) {
        return jdbc.sql("""
                        SELECT d.id, d.tipo_servicio, d.codigo_municipio, d.estado, d.fecha_recepcion,
                               EXISTS (SELECT 1 FROM reportes.evidencias e WHERE e.id_denuncia = d.id) AS con_evidencia
                        FROM reportes.denuncias d WHERE d.id = :id
                        """)
                .param("id", idDenuncia)
                .query((rs, n) -> new DenunciaGuardada(rs.getObject("id", UUID.class), rs.getString("tipo_servicio"),
                        rs.getString("codigo_municipio"), rs.getString("estado"), instante(rs, "fecha_recepcion"),
                        rs.getBoolean("con_evidencia")))
                .optional();
    }
}
