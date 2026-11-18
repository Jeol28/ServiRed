package co.edu.javeriana.servired.reportes.infraestructura.web;

import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Identifica al ciudadano de la petición por el claim {@code sub} del JWT, que
 * este servicio ya validó con el JWKS de Identidad. Nunca se toma de una
 * cabecera o del cuerpo enviados por el cliente.
 */
@Component
public class CiudadanoActual {

    public UUID id() {
        return UUID.fromString(jwt().getSubject());
    }

    /**
     * Creación de la cuenta (claim "creada" que firma Identidad, segundos epoch). Vacío si el
     * token no lo trae (emitido antes de la regla de antigüedad, ADR-22).
     */
    public Instant cuentaCreadaEn() {
        Object creada = jwt().getClaims().get("creada");
        return creada instanceof Number n ? Instant.ofEpochSecond(n.longValue()) : null;
    }

    private Jwt jwt() {
        var autenticacion = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacion == null || !(autenticacion.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("Petición sin JWT validado");
        }
        return jwt;
    }
}
