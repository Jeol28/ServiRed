package co.edu.javeriana.servired.persistencia.infraestructura.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;

/** Escribe respuestas application/problem+json (RFC 9457) desde filtros. */
final class Problemas {

    private Problemas() {
    }

    static void escribir(HttpServletResponse respuesta, int estado, String titulo, String detalle)
            throws IOException {
        respuesta.setStatus(estado);
        respuesta.setContentType("application/problem+json");
        String correlacion = MDC.get(FiltroCorrelacion.CLAVE_MDC);
        respuesta.getWriter().write("{\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\",\"correlationId\":\"%s\"}"
                .formatted(titulo, estado, detalle, correlacion));
    }
}
