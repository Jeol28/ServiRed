package co.edu.javeriana.servired.reportes.infraestructura.web;

import co.edu.javeriana.servired.reportes.aplicacion.puertos.RepositorioReportes;
import co.edu.javeriana.servired.reportes.dominio.EvidenciaInvalida;
import co.edu.javeriana.servired.reportes.dominio.ReglaIncumplida;
import co.edu.javeriana.servired.reportes.dominio.ReporteInvalido;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
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

    @ExceptionHandler(ReglaIncumplida.class)
    ProblemDetail reglaIncumplida(ReglaIncumplida e) {
        ProblemDetail p = problema(HttpStatus.CONFLICT, "Operación no permitida", e.getMessage());
        p.setProperty("motivo", e.motivo());
        return p;
    }

    @ExceptionHandler(EvidenciaInvalida.class)
    ProblemDetail evidenciaInvalida(EvidenciaInvalida e) {
        ProblemDetail p = problema(HttpStatus.UNPROCESSABLE_ENTITY, "Evidencia inválida", e.getMessage());
        p.setProperty("errores", List.of(Map.of("campo", "foto", "mensaje", e.getMessage())));
        return p;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail demasiadoGrande(MaxUploadSizeExceededException e) {
        return problema(HttpStatus.PAYLOAD_TOO_LARGE, "Archivo demasiado grande", "La fotografía supera 5 MB");
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            HttpMediaTypeNotSupportedException.class})
    ProblemDetail partesInvalidas(Exception e) {
        return problema(HttpStatus.BAD_REQUEST, "Solicitud inválida", "Faltan partes o parámetros, o no son válidos");
    }

    @ExceptionHandler(RepositorioReportes.ConflictoDeVersion.class)
    ProblemDetail concurrencia(RuntimeException e) {
        return problema(HttpStatus.CONFLICT, "Conflicto", "Hubo muchas validaciones simultáneas; intente de nuevo");
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

    /** Cualquier error no previsto: 500 sin detalles internos, con el correlationId para rastrearlo. */
    @ExceptionHandler(Exception.class)
    ProblemDetail inesperado(Exception e) {
        log.error("error no previsto", e);
        return problema(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno",
                "Ocurrió un error inesperado; use el correlationId para reportarlo");
    }

    private static ProblemDetail problema(HttpStatus estado, String titulo, String detalle) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(estado, detalle);
        p.setTitle(titulo);
        p.setProperty("correlationId", MDC.get(FiltroCorrelacion.CLAVE_MDC));
        return p;
    }
}
