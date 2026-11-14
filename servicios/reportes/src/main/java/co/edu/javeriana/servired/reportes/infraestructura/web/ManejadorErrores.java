package co.edu.javeriana.servired.reportes.infraestructura.web;

import co.edu.javeriana.servired.reportes.dominio.ReporteInvalido;
import co.edu.javeriana.servired.reportes.dominio.ReporteNoEncontrado;
import co.edu.javeriana.servired.reportes.infraestructura.persistencia.PersistenciaNoDisponible;
import com.fasterxml.jackson.databind.JsonMappingException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Errores en formato application/problem+json (RFC 9457) con el correlationId. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ManejadorErrores {

    private static final Logger log = LoggerFactory.getLogger(ManejadorErrores.class);

    @ExceptionHandler(ReporteInvalido.class)
    ProblemDetail invalido(ReporteInvalido e) {
        ProblemDetail p = problema(HttpStatus.BAD_REQUEST, "Reporte inválido", "Revise los campos indicados");
        p.setProperty("errores", e.errores().stream()
                .map(err -> Map.of("campo", err.campo(), "mensaje", err.mensaje())).toList());
        return p;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail ilegible(HttpMessageNotReadableException e) {
        ProblemDetail p = problema(HttpStatus.BAD_REQUEST, "Solicitud inválida", "El cuerpo no es un JSON válido");
        if (e.getCause() instanceof JsonMappingException mapeo && !mapeo.getPath().isEmpty()) {
            String campo = mapeo.getPath().stream()
                    .map(r -> r.getFieldName() != null ? r.getFieldName() : "[" + r.getIndex() + "]")
                    .collect(Collectors.joining("."));
            p.setProperty("errores", List.of(Map.of("campo", campo, "mensaje", "valor no permitido")));
        }
        return p;
    }

    @ExceptionHandler(ReporteNoEncontrado.class)
    ProblemDetail noEncontrado(ReporteNoEncontrado e) {
        return problema(HttpStatus.NOT_FOUND, "No encontrado", "El reporte no existe");
    }

    @ExceptionHandler(PersistenciaNoDisponible.class)
    ProblemDetail noDisponible(PersistenciaNoDisponible e) {
        log.error("no se pudo completar la operación: {}", e.getMessage());
        return problema(HttpStatus.SERVICE_UNAVAILABLE, "Servicio no disponible",
                "No fue posible registrar la solicitud; intente de nuevo con el mismo X-Correlation-Id");
    }

    private static ProblemDetail problema(HttpStatus estado, String titulo, String detalle) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(estado, detalle);
        p.setTitle(titulo);
        p.setProperty("correlationId", MDC.get(FiltroCorrelacion.CLAVE_MDC));
        return p;
    }
}
