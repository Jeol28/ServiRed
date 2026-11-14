package co.edu.javeriana.servired.reportes.infraestructura.web;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Identifica al ciudadano que hace la petición.
 *
 * <p><b>Provisional (fase 2):</b> mientras no existe Identidad se usa un
 * ciudadano de desarrollo fijo. En la fase 3 se reemplaza por el claim
 * {@code sub} del JWT validado con JWKS.
 */
@Component
public class CiudadanoActual {

    private final UUID ciudadanoDesarrollo;

    public CiudadanoActual(@Value("${servired.desarrollo.id-ciudadano}") UUID ciudadanoDesarrollo) {
        this.ciudadanoDesarrollo = ciudadanoDesarrollo;
    }

    public UUID id() {
        return ciudadanoDesarrollo;
    }
}
