package co.edu.javeriana.servired.reportes.aplicacion.puertos;

import co.edu.javeriana.servired.reportes.dominio.IdCorrelacion;
import co.edu.javeriana.servired.reportes.dominio.Municipio;
import co.edu.javeriana.servired.reportes.dominio.Ubicacion;

public interface CatalogoTerritorial {

    /** Municipio DIVIPOLA cuyo centroide es el más cercano (ADR-08). */
    Municipio resolverMunicipio(Ubicacion ubicacion, IdCorrelacion correlacion);
}
