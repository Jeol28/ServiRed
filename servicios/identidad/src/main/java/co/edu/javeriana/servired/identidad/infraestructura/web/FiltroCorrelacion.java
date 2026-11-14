package co.edu.javeriana.servired.identidad.infraestructura.web;

import co.edu.javeriana.servired.identidad.dominio.IdCorrelacion;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Coloca el X-Correlation-Id en el MDC para que aparezca en todos los logs
 * JSON de la petición, y lo devuelve en la respuesta.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FiltroCorrelacion extends OncePerRequestFilter {

    public static final String CLAVE_MDC = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest peticion, HttpServletResponse respuesta,
                                    FilterChain cadena) throws ServletException, IOException {
        IdCorrelacion id = IdCorrelacion.desdeCabecera(peticion.getHeader(IdCorrelacion.CABECERA));
        MDC.put(CLAVE_MDC, id.valor());
        respuesta.setHeader(IdCorrelacion.CABECERA, id.valor());
        try {
            cadena.doFilter(peticion, respuesta);
        } finally {
            MDC.remove(CLAVE_MDC);
        }
    }
}
