package co.edu.javeriana.servired.persistencia.aplicacion;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.CatalogoTerritorial;
import co.edu.javeriana.servired.persistencia.dominio.Municipio;
import co.edu.javeriana.servired.persistencia.dominio.OperacionNegocio;
import co.edu.javeriana.servired.persistencia.dominio.ServicioCliente;
import co.edu.javeriana.servired.persistencia.dominio.SolicitudRechazada;

/** resolverMunicipio: el municipio cuyo polígono oficial (DANE, MGN) contiene la ubicación (ADR-27). */
public class ResolverMunicipio {

    private final CatalogoTerritorial catalogo;

    public ResolverMunicipio(CatalogoTerritorial catalogo) {
        this.catalogo = catalogo;
    }

    public Municipio ejecutar(ServicioCliente servicio, double latitud, double longitud) {
        OperacionNegocio.RESOLVER_MUNICIPIO.autorizar(servicio);
        if (latitud < -4.3 || latitud > 13.5 || longitud < -82 || longitud > -66.8) {
            throw new SolicitudRechazada("Coordenadas fuera de Colombia");
        }
        return catalogo.municipioEn(latitud, longitud)
                .orElseThrow(() -> new IllegalStateException("Catálogo DIVIPOLA vacío"));
    }
}
