package co.edu.javeriana.servired.persistencia.infraestructura.web;

import co.edu.javeriana.servired.persistencia.dominio.AccesoDenegado;
import co.edu.javeriana.servired.persistencia.dominio.Conflicto;
import co.edu.javeriana.servired.persistencia.dominio.RecursoNoEncontrado;
import co.edu.javeriana.servired.persistencia.dominio.SolicitudRechazada;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Errores como application/problem+json, sin filtrar detalles internos. */
@RestControllerAdvice
public class ManejadorErrores {

    private static final Logger log = LoggerFactory.getLogger(ManejadorErrores.class);

    @ExceptionHandler(AccesoDenegado.class)
    ProblemDetail prohibido(AccesoDenegado e) {
        log.warn("operación denegada: {}", e.getMessage());
        return problema(HttpStatus.FORBIDDEN, "Prohibido", e.getMessage());
    }

    @ExceptionHandler(Conflicto.class)
    ProblemDetail conflicto(Conflicto e) {
        ProblemDetail p = problema(HttpStatus.CONFLICT, "Conflicto", e.getMessage());
        p.setProperty("motivo", e.motivo());
        return p;
    }

    @ExceptionHandler(RecursoNoEncontrado.class)
    ProblemDetail noEncontrado(RecursoNoEncontrado e) {
        return problema(HttpStatus.NOT_FOUND, "No encontrado", e.getMessage());
    }

    @ExceptionHandler(SolicitudRechazada.class)
    ProblemDetail rechazada(SolicitudRechazada e) {
        return problema(HttpStatus.CONFLICT, "Solicitud rechazada", e.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integridad(DataIntegrityViolationException e) {
        log.warn("violación de integridad: {}", e.getMostSpecificCause().getMessage());
        return problema(HttpStatus.CONFLICT, "Solicitud rechazada", "Viola una regla de integridad");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            IllegalArgumentException.class, org.springframework.web.multipart.MultipartException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    ProblemDetail invalida(Exception e) {
        return problema(HttpStatus.BAD_REQUEST, "Solicitud inválida", "Datos inválidos");
    }

    private static ProblemDetail problema(HttpStatus estado, String titulo, String detalle) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(estado, detalle);
        p.setTitle(titulo);
        p.setProperty("correlationId", MDC.get(FiltroCorrelacion.CLAVE_MDC));
        return p;
    }
}
