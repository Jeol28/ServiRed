package co.edu.javeriana.servired.persistencia.infraestructura.jdbc;

import static co.edu.javeriana.servired.persistencia.infraestructura.jdbc.RepositorioReportesJdbc.instante;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioAtencion;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioFase4.EvidenciaReferencia;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.CambiosCaso;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.Decision;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.Empresa;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.FiltroCasos;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.Moderacion;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.ModeracionNueva;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.PaginaCasos;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.PaginaModeraciones;
import co.edu.javeriana.servired.persistencia.dominio.CasoGuardado;
import co.edu.javeriana.servired.persistencia.dominio.CasoGuardado.EntradaHistoria;
import co.edu.javeriana.servired.persistencia.dominio.ReporteGuardado.EvidenciaGuardada;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class RepositorioAtencionJdbc implements RepositorioAtencion {

    /** Orden de la bandeja: prioridad descendente y, a igual prioridad, el más antiguo primero. */
    private static final String RANGO_PRIORIDAD =
            "CASE c.prioridad WHEN 'CRITICA' THEN 4 WHEN 'ALTA' THEN 3 WHEN 'MEDIA' THEN 2 ELSE 1 END";

    private final JdbcClient jdbc;

    public RepositorioAtencionJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Empresa> empresasConCobertura(String codigoMunicipio, String tipoServicio) {
        return jdbc.sql("""
                        SELECT e.id, e.nombre FROM catalogo.coberturas c
                        JOIN catalogo.empresas e ON e.id = c.id_empresa
                        WHERE c.codigo_municipio = :m AND c.tipo_servicio = :s ORDER BY e.id
                        """)
                .param("m", codigoMunicipio).param("s", tipoServicio)
                .query((rs, n) -> new Empresa(rs.getObject("id", UUID.class), rs.getString("nombre")))
                .list();
    }

    @Override
    public Optional<CasoGuardado> caso(UUID idCaso) {
        var historia = jdbc.sql("SELECT estado, fecha, detalle, autor FROM atencion.historia_caso WHERE id_caso = :id ORDER BY id")
                .param("id", idCaso)
                .query((rs, n) -> new EntradaHistoria(rs.getString("estado"), instante(rs, "fecha"),
                        rs.getString("detalle"), rs.getObject("autor", UUID.class)))
                .list();
        var evidencias = jdbc.sql("""
                        SELECT e.id, e.tipo_contenido, e.tamano_bytes, e.creada_en
                        FROM reportes.evidencias e JOIN atencion.casos c ON c.id_reporte = e.id_reporte
                        WHERE c.id = :id ORDER BY e.creada_en, e.id
                        """)
                .param("id", idCaso)
                .query((rs, n) -> new EvidenciaGuardada(rs.getObject("id", UUID.class), rs.getString("tipo_contenido"),
                        rs.getInt("tamano_bytes"), instante(rs, "creada_en")))
                .list();
        return jdbc.sql(CONSULTA + " WHERE c.id = :id")
                .param("id", idCaso)
                .query((rs, n) -> mapear(rs, historia, evidencias))
                .optional();
    }

    private static final String CONSULTA = """
            SELECT c.*, m.nombre AS nombre_municipio FROM atencion.casos c
            JOIN catalogo.municipios m ON m.codigo = c.codigo_municipio
            """;

    static CasoGuardado mapear(ResultSet rs, List<EntradaHistoria> historia, List<EvidenciaGuardada> evidencias)
            throws SQLException {
        return new CasoGuardado(
                rs.getObject("id", UUID.class), rs.getObject("id_reporte", UUID.class),
                rs.getString("tipo_servicio"), rs.getString("codigo_municipio"), rs.getString("nombre_municipio"),
                rs.getString("magnitud"), rs.getString("estado"), instante(rs, "abierto_en"),
                rs.getObject("id_empresa", UUID.class), rs.getString("prioridad"), rs.getBoolean("prioridad_manual"),
                rs.getBoolean("corte_validado"), (Double) rs.getObject("latitud"), (Double) rs.getObject("longitud"),
                rs.getString("descripcion"), opcional(rs, "inicio_estimado"), rs.getObject("asignado_a", UUID.class),
                rs.getString("resolucion"), opcional(rs, "resuelto_en"), opcional(rs, "cerrado_en"),
                rs.getInt("version"), historia, evidencias);
    }

    private static Instant opcional(ResultSet rs, String columna) throws SQLException {
        Timestamp t = rs.getTimestamp(columna);
        return t == null ? null : t.toInstant();
    }

    @Override
    public PaginaCasos listar(FiltroCasos f) {
        String donde = """
                 WHERE (CAST(:estado AS text) IS NULL OR c.estado = :estado)
                   AND (CAST(:servicio AS text) IS NULL OR c.tipo_servicio = :servicio)
                   AND (CAST(:municipio AS text) IS NULL OR c.codigo_municipio = :municipio)
                   AND (CAST(:departamento AS text) IS NULL OR left(c.codigo_municipio, 2) = :departamento)
                   AND (CAST(:prioridad AS text) IS NULL OR c.prioridad = :prioridad)
                   AND (CAST(:empresa AS uuid) IS NULL OR c.id_empresa = :empresa)
                   AND (NOT :sinEmpresa OR c.id_empresa IS NULL)
                   AND (CAST(:asignado AS uuid) IS NULL OR c.asignado_a = :asignado)
                   AND (CAST(:reporte AS uuid) IS NULL OR c.id_reporte = :reporte)
                """;
        int tamano = Math.clamp(f.tamano(), 1, 100);
        int pagina = Math.max(0, f.pagina());
        long total = parametros(jdbc.sql("SELECT count(*) FROM atencion.casos c" + donde), f)
                .query(Long.class).single();
        var elementos = parametros(jdbc.sql(CONSULTA + donde + " ORDER BY " + RANGO_PRIORIDAD
                        + " DESC, c.abierto_en, c.id LIMIT :limite OFFSET :desde"), f)
                .param("limite", tamano).param("desde", (long) pagina * tamano)
                .query((rs, n) -> mapear(rs, List.of(), List.of()))
                .list();
        return new PaginaCasos(elementos, total, pagina, tamano);
    }

    private static JdbcClient.StatementSpec parametros(JdbcClient.StatementSpec sql, FiltroCasos f) {
        return sql.param("estado", f.estado()).param("servicio", f.tipoServicio())
                .param("municipio", f.codigoMunicipio()).param("departamento", f.codigoDepartamento())
                .param("prioridad", f.prioridad())
                .param("empresa", f.idEmpresa()).param("sinEmpresa", f.soloSinEmpresa())
                .param("asignado", f.asignadoA()).param("reporte", f.idReporte());
    }

    @Override
    public boolean actualizar(UUID idCaso, CambiosCaso c) {
        return jdbc.sql("""
                        UPDATE atencion.casos SET
                            estado = COALESCE(:estado, estado),
                            prioridad = COALESCE(:prioridad, prioridad),
                            prioridad_manual = COALESCE(:manual, prioridad_manual),
                            asignado_a = CASE WHEN :quitar THEN NULL ELSE COALESCE(:asignado, asignado_a) END,
                            id_empresa = COALESCE(:empresa, id_empresa),
                            corte_validado = COALESCE(:validado, corte_validado),
                            resolucion = COALESCE(:resolucion, resolucion),
                            resuelto_en = COALESCE(:resuelto, resuelto_en),
                            cerrado_en = COALESCE(:cerrado, cerrado_en),
                            version = version + 1,
                            actualizado_en = now()
                        WHERE id = :id AND version = :version
                        """)
                .param("estado", c.estado()).param("prioridad", c.prioridad()).param("manual", c.prioridadManual())
                .param("quitar", c.quitarAsignacion()).param("asignado", c.asignadoA()).param("empresa", c.idEmpresa())
                .param("validado", c.corteValidado()).param("resolucion", c.resolucion())
                .param("resuelto", c.resueltoEn() == null ? null : Timestamp.from(c.resueltoEn()))
                .param("cerrado", c.cerradoEn() == null ? null : Timestamp.from(c.cerradoEn()))
                .param("id", idCaso).param("version", c.versionEsperada())
                .update() == 1;
    }

    @Override
    public boolean existeCaso(UUID idCaso) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM atencion.casos WHERE id = :id)")
                .param("id", idCaso).query(Boolean.class).single();
    }

    @Override
    public void agregarHistoria(UUID idCaso, String estado, Instant fecha, String detalle, UUID autor) {
        jdbc.sql("""
                        INSERT INTO atencion.historia_caso (id_caso, estado, fecha, detalle, autor)
                        VALUES (:id, :estado, :fecha, :detalle, :autor)
                        """)
                .param("id", idCaso).param("estado", estado).param("fecha", Timestamp.from(fecha))
                .param("detalle", detalle).param("autor", autor)
                .update();
    }

    @Override
    public boolean esOperadorDe(UUID idCuenta, UUID idEmpresa) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM identidad.cuentas
                                       WHERE id = :id AND rol = 'OPERADOR' AND id_empresa = :empresa
                                         AND activa AND eliminada_en IS NULL)
                        """)
                .param("id", idCuenta).param("empresa", idEmpresa).query(Boolean.class).single();
    }

    @Override
    public void abrirModeracionSiNoExiste(ModeracionNueva m) {
        jdbc.sql("""
                        INSERT INTO atencion.moderaciones (id_denuncia, id_empresa, recibida_en)
                        VALUES (:id, :empresa, :recibida) ON CONFLICT (id_denuncia) DO NOTHING
                        """)
                .param("id", m.idDenuncia()).param("empresa", m.idEmpresa())
                .param("recibida", Timestamp.from(m.recibidaEn()))
                .update();
    }

    private static final String CONSULTA_MODERACION = """
            SELECT mo.*, d.tipo_servicio, d.codigo_municipio, mu.nombre AS nombre_municipio, d.latitud, d.longitud,
                   d.descripcion,
                   COALESCE((SELECT array_agg(e.id ORDER BY e.creada_en) FROM reportes.evidencias e
                             WHERE e.id_denuncia = d.id), '{}') AS evidencias
            FROM atencion.moderaciones mo
            JOIN reportes.denuncias d ON d.id = mo.id_denuncia
            JOIN catalogo.municipios mu ON mu.codigo = d.codigo_municipio
            """;

    @Override
    public Optional<Moderacion> moderacion(UUID idDenuncia) {
        return jdbc.sql(CONSULTA_MODERACION + " WHERE mo.id_denuncia = :id")
                .param("id", idDenuncia).query((rs, n) -> moderacion(rs)).optional();
    }

    @Override
    public PaginaModeraciones listarModeraciones(String estado, UUID idEmpresa, int pagina, int tamano) {
        String donde = " WHERE (CAST(:estado AS text) IS NULL OR mo.estado = :estado)"
                + " AND (CAST(:empresa AS uuid) IS NULL OR mo.id_empresa = :empresa)";
        long total = jdbc.sql("SELECT count(*) FROM atencion.moderaciones mo" + donde)
                .param("estado", estado).param("empresa", idEmpresa).query(Long.class).single();
        var elementos = jdbc.sql(CONSULTA_MODERACION + donde + " ORDER BY mo.recibida_en, mo.id_denuncia LIMIT :l OFFSET :o")
                .param("estado", estado).param("empresa", idEmpresa).param("l", tamano).param("o", (long) pagina * tamano)
                .query((rs, n) -> moderacion(rs)).list();
        return new PaginaModeraciones(elementos, total, pagina, tamano);
    }

    private static Moderacion moderacion(ResultSet rs) throws SQLException {
        UUID[] evidencias = (UUID[]) rs.getArray("evidencias").getArray();
        return new Moderacion(rs.getObject("id_denuncia", UUID.class), rs.getString("tipo_servicio"),
                rs.getString("codigo_municipio"), rs.getString("nombre_municipio"), rs.getDouble("latitud"),
                rs.getDouble("longitud"), rs.getString("descripcion"), instante(rs, "recibida_en"),
                rs.getObject("id_empresa", UUID.class), rs.getString("estado"), rs.getString("nota"),
                rs.getObject("moderada_por", UUID.class), opcional(rs, "moderada_en"), rs.getInt("version"),
                List.of(evidencias));
    }

    @Override
    public boolean decidir(UUID idDenuncia, Decision d) {
        return jdbc.sql("""
                        UPDATE atencion.moderaciones SET estado = :estado, nota = :nota, moderada_por = :por,
                            moderada_en = :fecha, version = version + 1
                        WHERE id_denuncia = :id AND version = :version
                        """)
                .param("estado", d.estado()).param("nota", d.nota()).param("por", d.moderadaPor())
                .param("fecha", Timestamp.from(d.fecha())).param("id", idDenuncia).param("version", d.versionEsperada())
                .update() == 1;
    }

    @Override
    public Optional<EvidenciaReferencia> evidenciaDeReporte(UUID idReporte, UUID idEvidencia) {
        return jdbc.sql("SELECT clave_objeto, tipo_contenido FROM reportes.evidencias WHERE id = :e AND id_reporte = :r")
                .param("e", idEvidencia).param("r", idReporte)
                .query((rs, n) -> new EvidenciaReferencia(rs.getString("clave_objeto"), rs.getString("tipo_contenido")))
                .optional();
    }

    @Override
    public Optional<EvidenciaReferencia> evidenciaDeDenuncia(UUID idDenuncia, UUID idEvidencia) {
        return jdbc.sql("SELECT clave_objeto, tipo_contenido FROM reportes.evidencias WHERE id = :e AND id_denuncia = :d")
                .param("e", idEvidencia).param("d", idDenuncia)
                .query((rs, n) -> new EvidenciaReferencia(rs.getString("clave_objeto"), rs.getString("tipo_contenido")))
                .optional();
    }
}
