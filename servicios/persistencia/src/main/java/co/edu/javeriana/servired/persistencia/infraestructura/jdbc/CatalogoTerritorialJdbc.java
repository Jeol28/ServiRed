package co.edu.javeriana.servired.persistencia.infraestructura.jdbc;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.CatalogoTerritorial;
import co.edu.javeriana.servired.persistencia.dominio.Municipio;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Catálogo DIVIPOLA. El municipio de una ubicación es el polígono oficial del DANE que la
 * contiene (función {@code catalogo.municipio_en}, PostGIS; ADR-27); en el mar cerca de la
 * costa, el polígono más cercano. Si no hubiera polígonos cargados, se usa el centroide más
 * cercano (el método anterior, ADR-08).
 */
@Component
public class CatalogoTerritorialJdbc implements CatalogoTerritorial {

    private static final String SELECCION = """
            SELECT m.codigo, m.nombre, d.nombre AS departamento
            FROM catalogo.municipios m
            JOIN catalogo.departamentos d ON d.codigo = m.codigo_departamento
            """;

    private final JdbcClient jdbc;

    public CatalogoTerritorialJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Municipio> municipioEn(double latitud, double longitud) {
        return jdbc.sql(SELECCION + "WHERE m.codigo = catalogo.municipio_en(:lat, :lon)")
                .param("lat", latitud)
                .param("lon", longitud)
                .query(CatalogoTerritorialJdbc::municipio)
                .optional()
                .or(() -> centroideMasCercano(latitud, longitud));
    }

    private Optional<Municipio> centroideMasCercano(double latitud, double longitud) {
        return jdbc.sql(SELECCION + """
                        ORDER BY power(m.latitud - :lat, 2)
                               + power((m.longitud - :lon) * cos(radians(:lat)), 2)
                        LIMIT 1
                        """)
                .param("lat", latitud)
                .param("lon", longitud)
                .query(CatalogoTerritorialJdbc::municipio)
                .optional();
    }

    private static Municipio municipio(java.sql.ResultSet rs, int fila) throws java.sql.SQLException {
        return new Municipio(rs.getString("codigo"), rs.getString("nombre"), rs.getString("departamento"));
    }

    @Override
    public boolean existeMunicipio(String codigo) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM catalogo.municipios WHERE codigo = :codigo)")
                .param("codigo", codigo)
                .query(Boolean.class)
                .single();
    }
}
