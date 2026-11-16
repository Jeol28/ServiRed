package co.edu.javeriana.servired.identidad.infraestructura.seguridad;

import co.edu.javeriana.servired.identidad.infraestructura.web.FiltroCorrelacion;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/** 401 y 403 de Spring Security en formato application/problem+json. */
public final class RespuestasSeguridad {

    private RespuestasSeguridad() {
    }

    public static AuthenticationEntryPoint noAutenticado() {
        return (peticion, respuesta, e) -> escribir(respuesta, 401, "No autenticado",
                "Se requiere un token de acceso válido");
    }

    public static AccessDeniedHandler prohibido() {
        return (peticion, respuesta, e) -> escribir(respuesta, 403, "Prohibido",
                "Su rol no tiene permiso para esta operación");
    }

    private static void escribir(HttpServletResponse r, int estado, String titulo, String detalle) throws IOException {
        r.setStatus(estado);
        r.setContentType("application/problem+json");
        if (estado == 401) {
            r.setHeader("WWW-Authenticate", "Bearer");
        }
        r.getWriter().write("{\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\",\"correlationId\":\"%s\"}"
                .formatted(titulo, estado, detalle, MDC.get(FiltroCorrelacion.CLAVE_MDC)));
    }
}
