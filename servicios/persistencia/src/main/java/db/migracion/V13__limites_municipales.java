package db.migracion;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.zip.GZIPInputStream;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Polígonos municipales oficiales (DANE, MGN 2024) para resolver el municipio de un reporte
 * por contención en lugar del centroide más cercano (ADR-27).
 *
 * <p>Los datos vienen de {@code db/datos/limites-municipales-mgn2024.bin.gz}, generado por
 * {@code herramientas/generar-limites-municipales.sh}. Es una migración Java porque el archivo
 * es binario (TWKB comprimido, ~5 MB).
 *
 * <p>PostGIS: si ya está instalado se usa su esquema (en Supabase suele ser
 * {@code extensions}); si no, se instala en {@code extensions} si ese esquema existe, o en
 * {@code public}. Todos los nombres van calificados con ese esquema, y la consulta es la
 * función {@code catalogo.municipio_en} con su propio search_path: no depende del
 * search_path de la conexión (Flyway usa {@code infraestructura}).
 */
public class V13__limites_municipales extends BaseJavaMigration {

    static final String DATOS = "db/datos/limites-municipales-mgn2024.bin.gz";
    static final String FUENTE = "DANE MGN 2024 (MGN_ADM_MPIO_GRAFICO), simplificado a 0,0001°";

    @Override
    public void migrate(Context contexto) throws Exception {
        Connection c = contexto.getConnection();
        String g = comillas(esquemaPostgis(c));
        try (Statement s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE catalogo.limites_municipios (
                        codigo CHAR(5) PRIMARY KEY REFERENCES catalogo.municipios (codigo),
                        geom   %1$s.geometry(MultiPolygon, 4326) NOT NULL,
                        fuente TEXT NOT NULL
                    )""".formatted(g));
            s.execute("COMMENT ON TABLE catalogo.limites_municipios IS "
                    + "'Polígonos municipales oficiales para ubicar reportes (ADR-27)'");
        }
        int cargados = cargar(c, g);
        if (cargados < 1100) {
            throw new IllegalStateException("Se esperaban ~1.121 polígonos municipales y se cargaron " + cargados);
        }
        try (Statement s = c.createStatement()) {
            s.execute("CREATE INDEX idx_limites_municipios_geom ON catalogo.limites_municipios USING gist (geom)");
            s.execute("ANALYZE catalogo.limites_municipios");
            // Contención (ST_Covers incluye el borde); si el punto no cae en ningún polígono
            // (mar cerca de la costa), el polígono más cercano (KNN sobre el índice GIST).
            s.execute("""
                    CREATE FUNCTION catalogo.municipio_en(latitud double precision, longitud double precision)
                    RETURNS char(5) LANGUAGE sql STABLE PARALLEL SAFE
                    SET search_path = pg_catalog, %1$s
                    AS $$
                        SELECT codigo FROM (
                            (SELECT l.codigo, 0 AS orden FROM catalogo.limites_municipios l
                             WHERE %1$s.ST_Covers(l.geom, %1$s.ST_SetSRID(%1$s.ST_MakePoint(longitud, latitud), 4326))
                             LIMIT 1)
                            UNION ALL
                            (SELECT l.codigo, 1 FROM catalogo.limites_municipios l
                             ORDER BY l.geom OPERATOR(%1$s.<->) %1$s.ST_SetSRID(%1$s.ST_MakePoint(longitud, latitud), 4326)
                             LIMIT 1)
                        ) candidatos
                        ORDER BY orden
                        LIMIT 1
                    $$""".formatted(g));
        }
    }

    /** Esquema donde está (o queda) PostGIS. */
    static String esquemaPostgis(Connection c) throws SQLException {
        String esquema = consultar(c, """
                SELECT n.nspname FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
                WHERE e.extname = 'postgis'""");
        if (esquema != null) {
            return esquema;
        }
        String destino = consultar(c, "SELECT nspname FROM pg_namespace WHERE nspname = 'extensions'");
        destino = destino == null ? "public" : destino;
        try (Statement s = c.createStatement()) {
            s.execute("CREATE EXTENSION IF NOT EXISTS postgis SCHEMA " + comillas(destino));
        }
        return destino;
    }

    private static int cargar(Connection c, String g) throws SQLException, IOException {
        String sql = """
                INSERT INTO catalogo.limites_municipios (codigo, geom, fuente)
                VALUES (?, %1$s.ST_Multi(%1$s.ST_CollectionExtract(%1$s.ST_MakeValid(
                            %1$s.ST_SetSRID(%1$s.ST_GeomFromTWKB(?), 4326)), 3)), ?)""".formatted(g);
        int n = 0;
        try (InputStream recurso = V13__limites_municipales.class.getClassLoader().getResourceAsStream(DATOS)) {
            if (recurso == null) {
                throw new IllegalStateException("Falta el recurso " + DATOS);
            }
            try (var entrada = new DataInputStream(new GZIPInputStream(recurso, 1 << 16));
                 PreparedStatement p = c.prepareStatement(sql)) {
                byte[] codigo = new byte[5];
                while (true) {
                    try {
                        entrada.readFully(codigo);
                    } catch (EOFException fin) {
                        break;
                    }
                    byte[] twkb = new byte[entrada.readInt()];
                    entrada.readFully(twkb);
                    p.setString(1, new String(codigo, StandardCharsets.US_ASCII));
                    p.setBytes(2, twkb);
                    p.setString(3, FUENTE);
                    p.addBatch();
                    if (++n % 100 == 0) {
                        p.executeBatch();
                    }
                }
                p.executeBatch();
            }
        }
        return n;
    }

    private static String consultar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            return r.next() ? r.getString(1) : null;
        }
    }

    private static String comillas(String identificador) {
        return "\"" + identificador.replace("\"", "\"\"") + "\"";
    }
}
