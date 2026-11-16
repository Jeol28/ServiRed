package co.edu.javeriana.servired.identidad.infraestructura.web;

import co.edu.javeriana.servired.identidad.aplicacion.Solicitante;
import co.edu.javeriana.servired.identidad.dominio.IdCorrelacion;
import co.edu.javeriana.servired.identidad.dominio.Rol;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.oauth2.jwt.Jwt;

/** Datos de la petición en curso: correlación (MDC) y solicitante (JWT ya validado). */
final class Contexto {

    private Contexto() {
    }

    static IdCorrelacion correlacion() {
        return new IdCorrelacion(MDC.get(FiltroCorrelacion.CLAVE_MDC));
    }

    static Solicitante solicitante(Jwt jwt) {
        return new Solicitante(UUID.fromString(jwt.getSubject()), Rol.valueOf(jwt.getClaimAsString("rol")));
    }
}
