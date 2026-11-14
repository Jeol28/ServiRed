package co.edu.javeriana.servired.persistencia.infraestructura.web;

import co.edu.javeriana.servired.persistencia.aplicacion.ResolverMunicipio;
import co.edu.javeriana.servired.persistencia.dominio.Municipio;
import co.edu.javeriana.servired.persistencia.dominio.ServicioCliente;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/interno/v1/catalogo")
public class ControladorCatalogo {

    private final ResolverMunicipio resolverMunicipio;

    public ControladorCatalogo(ResolverMunicipio resolverMunicipio) {
        this.resolverMunicipio = resolverMunicipio;
    }

    @GetMapping("/municipio-cercano")
    public Municipio municipioCercano(
            @RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente servicio,
            @RequestParam double latitud, @RequestParam double longitud) {
        return resolverMunicipio.ejecutar(servicio, latitud, longitud);
    }
}
