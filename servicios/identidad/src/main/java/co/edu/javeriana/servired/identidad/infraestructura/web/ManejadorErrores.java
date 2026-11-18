package co.edu.javeriana.servired.identidad.infraestructura.web;

import co.edu.javeriana.servired.identidad.dominio.DatosInvalidos;
import co.edu.javeriana.servired.identidad.dominio.Excepciones.CorreoYaRegistrado;
import co.edu.javeriana.servired.identidad.dominio.Excepciones.CredencialesInvalidas;
import co.edu.javeriana.servired.identidad.dominio.Excepciones.CuentaNoEncontrada;
import co.edu.javeriana.servired.identidad.dominio.Excepciones.OperacionNoPermitida;
import co.edu.javeriana.servired.identidad.dominio.Excepciones.SesionInvalida;
import co.edu.javeriana.servired.identidad.infraestructura.persistencia.PersistenciaNoDisponible;
import com.fasterxml.jackson.databind.JsonMappingException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Errores en application/problem+json, sin datos personales en el detalle ni en los logs. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ManejadorErrores {

    private static final Logger log = LoggerFactory.getLogger(ManejadorErrores.class);

    @ExceptionHandler(DatosInvalidos.class)
    ProblemDetail invalidos(DatosInvalidos e) {
        ProblemDetail p = problema(HttpStatus.BAD_REQUEST, "Datos inválidos", "Revise los campos indicados");
        p.setProperty("errores", e.errores().stream()
                .map(err -> Map.of("campo", err.campo(), "mensaje", err.mensaje())).toList());
        return p;
    }

    @ExceptionHandler({CredencialesInvalidas.class, SesionInvalida.class})
    ResponseEntity<ProblemDetail> noAutenticado(RuntimeException e) {
        log.info("autenticación rechazada: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(problema(HttpStatus.UNAUTHORIZED, "No autenticado", e.getMessage()));
    }

    @ExceptionHandler(co.edu.javeriana.servired.identidad.dominio.Excepciones.CorrelacionAjena.class)
    ProblemDetail correlacionAjena(RuntimeException e) {
        ProblemDetail p = problema(HttpStatus.CONFLICT, "Conflicto", e.getMessage());
        p.setProperty("motivo", "CORRELACION_AJENA");
        return p;
    }

    @ExceptionHandler(CorreoYaRegistrado.class)
    ProblemDetail correoRepetido(CorreoYaRegistrado e) {
        return problema(HttpStatus.CONFLICT, "Conflicto", e.getMessage());
    }

    @ExceptionHandler(CuentaNoEncontrada.class)
    ProblemDetail noEncontrada(CuentaNoEncontrada e) {
        return problema(HttpStatus.NOT_FOUND, "No encontrado", e.getMessage());
    }

    @ExceptionHandler(OperacionNoPermitida.class)
    ProblemDetail noPermitida(OperacionNoPermitida e) {
        return problema(HttpStatus.FORBIDDEN, "Prohibido", e.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ProblemDetail ilegible(Exception e) {
        ProblemDetail p = problema(HttpStatus.BAD_REQUEST, "Solicitud inválida", "El cuerpo o los parámetros no son válidos");
        if (e.getCause() instanceof JsonMappingException m && !m.getPath().isEmpty()) {
            String campo = m.getPath().stream().map(r -> r.getFieldName() != null ? r.getFieldName() : "[" + r.getIndex() + "]")
                    .collect(Collectors.joining("."));
            p.setProperty("errores", List.of(Map.of("campo", campo, "mensaje", "valor no permitido")));
        }
        return p;
    }

    @ExceptionHandler(PersistenciaNoDisponible.class)
    ProblemDetail noDisponible(PersistenciaNoDisponible e) {
        log.error("no se pudo completar la operación: {}", e.getMessage());
        return problema(HttpStatus.SERVICE_UNAVAILABLE, "Servicio no disponible", "Intente de nuevo en unos segundos");
    }

    private static ProblemDetail problema(HttpStatus estado, String titulo, String detalle) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(estado, detalle);
        p.setTitle(titulo);
        p.setProperty("correlationId", MDC.get(FiltroCorrelacion.CLAVE_MDC));
        return p;
    }
}
