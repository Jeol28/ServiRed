package co.edu.javeriana.servired.persistencia.infraestructura.jdbc;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.CatalogoTerritorial;
import co.edu.javeriana.servired.persistencia.dominio.Municipio;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Catálogo DIVIPOLA. El municipio más cercano se calcula con distancia
 * equirectangular al centroide (suficiente para ~1.100 puntos y para
 * distancias cortas; no requiere PostGIS).
 */
@Component
public class CatalogoTerritorialJdbc implements CatalogoTerritorial {

    private final JdbcClient jdbc;

    public CatalogoTerritorialJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Municipio> municipioMasCercano(double latitud, double longitud) {
        return jdbc.sql("""
                        SELECT m.codigo, m.nombre, d.nombre AS departamento
                        FROM catalogo.municipios m
                        JOIN catalogo.departamentos d ON d.codigo = m.codigo_departamento
                        ORDER BY power(m.latitud - :lat, 2)
                               + power((m.longitud - :lon) * cos(radians(:lat)), 2)
                        LIMIT 1
                        """)
                .param("lat", latitud)
                .param("lon", longitud)
                .query((rs, n) -> new Municipio(rs.getString("codigo"), rs.getString("nombre"),
                        rs.getString("departamento")))
                .optional();
    }

    @Override
    public boolean existeMunicipio(String codigo) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM catalogo.municipios WHERE codigo = :codigo)")
                .param("codigo", codigo)
                .query(Boolean.class)
                .single();
    }
}
