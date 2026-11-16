package co.edu.javeriana.servired.identidad.infraestructura.web;

import co.edu.javeriana.servired.identidad.infraestructura.seguridad.ClavesFirma;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Claves públicas de firma (RFC 7517). Lo consultan el gateway y los servicios por la red interna. */
@RestController
public class ControladorJwks {

    private final ClavesFirma claves;

    public ControladorJwks(ClavesFirma claves) {
        this.claves = claves;
    }

    @GetMapping(value = "/.well-known/jwks.json", produces = "application/json")
    public Map<String, Object> jwks() {
        return claves.jwksPublico().toJSONObject();
    }
}
