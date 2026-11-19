package co.edu.javeriana.servired.persistencia.aplicacion.puertos;

import co.edu.javeriana.servired.persistencia.dominio.Municipio;
import java.util.Optional;

public interface CatalogoTerritorial {

    /** Municipio que contiene las coordenadas según los límites oficiales (ADR-27). */
    Optional<Municipio> municipioEn(double latitud, double longitud);

    boolean existeMunicipio(String codigo);
}
