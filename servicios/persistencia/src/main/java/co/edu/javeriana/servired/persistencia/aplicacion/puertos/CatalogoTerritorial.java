package co.edu.javeriana.servired.persistencia.aplicacion.puertos;

import co.edu.javeriana.servired.persistencia.dominio.Municipio;
import java.util.Optional;

public interface CatalogoTerritorial {

    /** Municipio cuyo centroide está más cerca de las coordenadas. */
    Optional<Municipio> municipioMasCercano(double latitud, double longitud);

    boolean existeMunicipio(String codigo);
}
