package co.edu.javeriana.servired.persistencia.infraestructura.web;

import co.edu.javeriana.servired.persistencia.dominio.IdCorrelacion;
import co.edu.javeriana.servired.persistencia.dominio.ServicioCliente;
import co.edu.javeriana.servired.persistencia.infraestructura.config.ClavesServicio;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Protege /interno/**: exige X-Clave-Servicio válida (identifica al servicio
 * que llama) y un X-Correlation-Id UUID, porque es la clave de idempotencia y
 * no puede inventarse aquí.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class FiltroClaveServicio extends OncePerRequestFilter {

    public static final String CABECERA_CLAVE = "X-Clave-Servicio";
    public static final String ATRIBUTO_SERVICIO = "servired.servicioCliente";

    private final ClavesServicio claves;

    public FiltroClaveServicio(ClavesServicio claves) {
        this.claves = claves;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest peticion) {
        return !peticion.getRequestURI().startsWith("/interno/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest peticion, HttpServletResponse respuesta,
                                    FilterChain cadena) throws ServletException, IOException {
        var servicio = claves.identificar(peticion.getHeader(CABECERA_CLAVE));
        if (servicio.isEmpty()) {
            Problemas.escribir(respuesta, 401, "No autorizado", "Falta X-Clave-Servicio o no es válida");
            return;
        }
        if (!esUuid(peticion.getHeader(IdCorrelacion.CABECERA))) {
            Problemas.escribir(respuesta, 400, "Solicitud inválida", "X-Correlation-Id (UUID) es obligatorio");
            return;
        }
        ServicioCliente cliente = servicio.get();
        peticion.setAttribute(ATRIBUTO_SERVICIO, cliente);
        MDC.put("servicioCliente", cliente.nombre());
        try {
            cadena.doFilter(peticion, respuesta);
        } finally {
            MDC.remove("servicioCliente");
        }
    }

    private static boolean esUuid(String valor) {
        if (valor == null || valor.length() != 36) {
            return false;
        }
        try {
            UUID.fromString(valor);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
