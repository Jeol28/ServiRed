package db.migracion;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.zip.GZIPInputStream;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Zonas de servicio (ADR-35): cada empresa prestadora es dueña de una zona por servicio, y solo
 * ella gestiona los reportes que caen allí.
 *
 * <ul>
 *   <li><b>URBANA</b> ({@code <municipio>-U}): la cabecera municipal del DANE (V14).</li>
 *   <li><b>RURAL</b> ({@code <municipio>-R}): el resto del municipio.</li>
 *   <li><b>LOCALIDAD</b> ({@code <municipio>-L..}/{@code -C..}): localidades y comunas oficiales de
 *       Bogotá, Medellín, Cali y Barranquilla. Solo tienen dueño propio si difiere del de la ciudad
 *       (el aseo de Bogotá); si no, manda la zona urbana o rural que contiene el punto.</li>
 * </ul>
 *
 * <p>La cobertura queda en {@code catalogo.coberturas_zona}, con una sola empresa por zona y
 * servicio (llave primaria). La carga V21 la llena y V22 retira las empresas de ejemplo.
 */
public class V20__zonas_de_servicio extends BaseJavaMigration {

    static final String DATOS = "db/datos/zonas-comunas.tsv.gz";
    static final double TOLERANCIA = 0.00005;

    @Override
    public void migrate(Context contexto) throws Exception {
        Connection c = contexto.getConnection();
        String g = V13__limites_municipales.comillas(V13__limites_municipales.esquemaPostgis(c));
        try (Statement s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE catalogo.zonas (
                        id               TEXT    PRIMARY KEY,
                        codigo_municipio CHAR(5) NOT NULL REFERENCES catalogo.municipios (codigo),
                        tipo             TEXT    NOT NULL CHECK (tipo IN ('URBANA', 'RURAL', 'LOCALIDAD')),
                        nombre           TEXT    NOT NULL,
                        geom             %1$s.geometry(MultiPolygon, 4326),
                        fuente           TEXT    NOT NULL,
                        CHECK ((tipo = 'LOCALIDAD') = (geom IS NOT NULL))
                    )""".formatted(g));
            s.execute("COMMENT ON TABLE catalogo.zonas IS 'Zonas de servicio: cabecera, resto rural y "
                    + "localidades o comunas (ADR-35). Solo las localidades guardan polígono propio'");
            s.execute("CREATE INDEX idx_zonas_municipio ON catalogo.zonas (codigo_municipio)");
            s.execute("""
                    INSERT INTO catalogo.zonas (id, codigo_municipio, tipo, nombre, fuente)
                    SELECT m.codigo || '-U', m.codigo, 'URBANA', 'Cabecera de ' || initcap(m.nombre),
                           'DANE MGN 2024, zona urbana de la cabecera (V14)'
                    FROM catalogo.municipios m JOIN catalogo.cabeceras_municipales c ON c.codigo = m.codigo""");
            s.execute("""
                    INSERT INTO catalogo.zonas (id, codigo_municipio, tipo, nombre, fuente)
                    SELECT m.codigo || '-R', m.codigo, 'RURAL', 'Zona rural de ' || initcap(m.nombre),
                           'DANE MGN 2024, municipio fuera de la cabecera (V13 y V14)'
                    FROM catalogo.municipios m""");
            s.execute("""
                    CREATE TABLE catalogo.coberturas_zona (
                        id_zona       TEXT NOT NULL REFERENCES catalogo.zonas (id),
                        tipo_servicio TEXT NOT NULL REFERENCES catalogo.tipos_servicio (codigo),
                        id_empresa    UUID NOT NULL REFERENCES catalogo.empresas (id),
                        fuente        TEXT NOT NULL,
                        PRIMARY KEY (id_zona, tipo_servicio)
                    )""");
            s.execute("COMMENT ON TABLE catalogo.coberturas_zona IS "
                    + "'Única empresa dueña de cada zona y servicio (ADR-35)'");
            s.execute("CREATE INDEX idx_coberturas_zona_empresa ON catalogo.coberturas_zona (id_empresa)");
            s.execute("CREATE TEMP TABLE partes_localidad (id TEXT, nombre TEXT, fuente TEXT, geojson TEXT) "
                    + "ON COMMIT DROP");
        }
        int partes = cargarLocalidades(c);
        try (Statement s = c.createStatement()) {
            int zonas = s.executeUpdate("""
                    INSERT INTO catalogo.zonas (id, codigo_municipio, tipo, nombre, geom, fuente)
                    SELECT id, left(id, 5), 'LOCALIDAD', min(nombre),
                           %1$s.ST_Multi(%1$s.ST_CollectionExtract(%1$s.ST_MakeValid(%1$s.ST_SimplifyPreserveTopology(
                               %1$s.ST_Union(%1$s.ST_MakeValid(%1$s.ST_SetSRID(%1$s.ST_GeomFromGeoJSON(geojson), 4326))),
                               %2$s)), 3)),
                           min(fuente)
                    FROM partes_localidad GROUP BY id""".formatted(g, TOLERANCIA));
            if (zonas < 60) {
                throw new IllegalStateException("Se esperaban 68 localidades y comunas (" + partes
                        + " polígonos) y se cargaron " + zonas);
            }
            s.execute("CREATE INDEX idx_zonas_geom ON catalogo.zonas USING gist (geom) WHERE geom IS NOT NULL");
            s.execute("ANALYZE catalogo.zonas");
            s.execute(FUNCIONES.formatted(g));
        }
    }

    private static int cargarLocalidades(Connection c) throws Exception {
        int n = 0;
        try (InputStream recurso = V20__zonas_de_servicio.class.getClassLoader().getResourceAsStream(DATOS)) {
            if (recurso == null) {
                throw new IllegalStateException("Falta el recurso " + DATOS);
            }
            try (var lector = new BufferedReader(new InputStreamReader(new GZIPInputStream(recurso),
                    StandardCharsets.UTF_8));
                 PreparedStatement p = c.prepareStatement("INSERT INTO partes_localidad VALUES (?, ?, ?, ?)")) {
                String linea;
                while ((linea = lector.readLine()) != null) {
                    String[] campos = linea.split("\t", 4);
                    for (int i = 0; i < 4; i++) {
                        p.setString(i + 1, campos[i]);
                    }
                    p.addBatch();
                    if (++n % 50 == 0) {
                        p.executeBatch();
                    }
                }
                p.executeBatch();
            }
        }
        return n;
    }

    /**
     * Funciones de cobertura, con su propio search_path para no depender del de la conexión.
     * <ul>
     *   <li>{@code zona_en(lat, lon)}: la zona más específica que contiene el punto (localidad o
     *       comuna si la hay; si no, la cabecera o el resto rural de su municipio).</li>
     *   <li>{@code empresa_en(lat, lon, servicio)}: la dueña del servicio en ese punto. Una
     *       localidad sin dueño propio hereda el de su cabecera o resto rural.</li>
     *   <li>{@code empresa_de_municipio(codigo, servicio)}: sin ubicación exacta (denuncias
     *       anónimas), la dueña de la cabecera; si el municipio no tiene cabecera, la del resto rural.</li>
     * </ul>
     */
    static final String FUNCIONES = """
            CREATE FUNCTION catalogo.zona_base_en(latitud double precision, longitud double precision)
            RETURNS TEXT LANGUAGE sql STABLE PARALLEL SAFE
            SET search_path = pg_catalog, %1$s
            AS $$
                WITH m AS (SELECT catalogo.municipio_en(latitud, longitud) AS codigo)
                SELECT CASE WHEN EXISTS (
                                SELECT 1 FROM catalogo.cabeceras_municipales c
                                WHERE c.codigo = m.codigo
                                  AND ST_Covers(c.geom, ST_SetSRID(ST_MakePoint(longitud, latitud), 4326)))
                            THEN m.codigo || '-U' ELSE m.codigo || '-R' END
                FROM m WHERE m.codigo IS NOT NULL
            $$;

            CREATE FUNCTION catalogo.zona_en(latitud double precision, longitud double precision)
            RETURNS TEXT LANGUAGE sql STABLE PARALLEL SAFE
            SET search_path = pg_catalog, %1$s
            AS $$
                SELECT coalesce(
                    (SELECT z.id FROM catalogo.zonas z
                     WHERE z.geom IS NOT NULL
                       AND z.codigo_municipio = left(catalogo.zona_base_en(latitud, longitud), 5)
                       AND ST_Covers(z.geom, ST_SetSRID(ST_MakePoint(longitud, latitud), 4326))
                     ORDER BY z.id LIMIT 1),
                    catalogo.zona_base_en(latitud, longitud))
            $$;

            CREATE FUNCTION catalogo.empresa_en(latitud double precision, longitud double precision, servicio TEXT)
            RETURNS TABLE (id_zona TEXT, id_empresa UUID) LANGUAGE sql STABLE PARALLEL SAFE
            SET search_path = pg_catalog, %1$s
            AS $$
                WITH z AS (SELECT catalogo.zona_en(latitud, longitud) AS zona,
                                  catalogo.zona_base_en(latitud, longitud) AS base)
                SELECT z.zona, coalesce(
                    (SELECT c.id_empresa FROM catalogo.coberturas_zona c
                     WHERE c.id_zona = z.zona AND c.tipo_servicio = servicio),
                    (SELECT c.id_empresa FROM catalogo.coberturas_zona c
                     WHERE c.id_zona = z.base AND c.tipo_servicio = servicio))
                FROM z WHERE z.zona IS NOT NULL
            $$;

            CREATE FUNCTION catalogo.empresa_de_municipio(codigo CHAR(5), servicio TEXT)
            RETURNS TABLE (id_zona TEXT, id_empresa UUID) LANGUAGE sql STABLE PARALLEL SAFE
            AS $$
                SELECT z.id, c.id_empresa
                FROM catalogo.zonas z
                LEFT JOIN catalogo.coberturas_zona c ON c.id_zona = z.id AND c.tipo_servicio = servicio
                WHERE z.codigo_municipio = codigo AND z.tipo IN ('URBANA', 'RURAL')
                ORDER BY z.tipo = 'URBANA' DESC LIMIT 1
            $$;
            """;
}
