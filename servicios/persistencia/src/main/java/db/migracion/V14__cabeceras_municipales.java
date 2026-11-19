package db.migracion;

import java.sql.Connection;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Cabeceras municipales oficiales (DANE, MGN 2024, zonas urbanas de clase 1) para la
 * privacidad rural del mapa público (ADR-28): fuera de ellas las celdas son más grandes y se
 * exigen más reportes para publicarlas.
 *
 * <p>Los centros poblados (clase 2) cuentan como rurales, igual que en el DANE. La vista de
 * Indicadores guarda si cada reporte está en una cabecera ({@code en_cabecera}) para no
 * evaluar polígonos al agregar; esta migración la completa para los reportes existentes.
 */
public class V14__cabeceras_municipales extends BaseJavaMigration {

    static final String DATOS = "db/datos/cabeceras-municipales-mgn2024.bin.gz";
    static final String FUENTE = "DANE MGN 2024 (MGN_URB_ZONA_URBANA, clase 1), simplificado a 0,00005°";

    @Override
    public void migrate(Context contexto) throws Exception {
        Connection c = contexto.getConnection();
        String g = V13__limites_municipales.comillas(V13__limites_municipales.esquemaPostgis(c));
        try (Statement s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE catalogo.cabeceras_municipales (
                        codigo CHAR(5) PRIMARY KEY REFERENCES catalogo.municipios (codigo),
                        geom   %1$s.geometry(MultiPolygon, 4326) NOT NULL,
                        fuente TEXT NOT NULL
                    )""".formatted(g));
            s.execute("COMMENT ON TABLE catalogo.cabeceras_municipales IS "
                    + "'Áreas urbanas de las cabeceras municipales: privacidad del mapa público (ADR-28)'");
        }
        int cargadas = V13__limites_municipales.cargar(c, DATOS, """
                INSERT INTO catalogo.cabeceras_municipales (codigo, geom, fuente)
                VALUES (?, %1$s.ST_Multi(%1$s.ST_CollectionExtract(%1$s.ST_MakeValid(
                            %1$s.ST_SetSRID(%1$s.ST_GeomFromTWKB(?), 4326)), 3)), ?)""".formatted(g), FUENTE);
        if (cargadas < 1000) {
            throw new IllegalStateException("Se esperaban ~1.103 cabeceras municipales y se cargaron " + cargadas);
        }
        try (Statement s = c.createStatement()) {
            s.execute("CREATE INDEX idx_cabeceras_municipales_geom ON catalogo.cabeceras_municipales USING gist (geom)");
            s.execute("ANALYZE catalogo.cabeceras_municipales");
            s.execute("""
                    CREATE FUNCTION catalogo.en_cabecera(latitud double precision, longitud double precision)
                    RETURNS boolean LANGUAGE sql STABLE PARALLEL SAFE
                    SET search_path = pg_catalog, %1$s
                    AS $$
                        SELECT EXISTS (
                            SELECT 1 FROM catalogo.cabeceras_municipales c
                            WHERE %1$s.ST_Covers(c.geom, %1$s.ST_SetSRID(%1$s.ST_MakePoint(longitud, latitud), 4326)))
                    $$""".formatted(g));
            s.execute("ALTER TABLE indicadores.vista_reportes ADD COLUMN en_cabecera BOOLEAN");
            s.execute("COMMENT ON COLUMN indicadores.vista_reportes.en_cabecera IS "
                    + "'Si el reporte está en el área urbana de una cabecera municipal (ADR-28); null = sin ubicación'");
            s.execute("UPDATE indicadores.vista_reportes SET en_cabecera = catalogo.en_cabecera(latitud, longitud) "
                    + "WHERE latitud IS NOT NULL AND longitud IS NOT NULL");
        }
    }
}
