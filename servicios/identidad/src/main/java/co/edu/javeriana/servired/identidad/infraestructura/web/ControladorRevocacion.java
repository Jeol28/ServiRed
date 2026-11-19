package co.edu.javeriana.servired.identidad.infraestructura.web;

import co.edu.javeriana.servired.identidad.aplicacion.Autenticacion;
import co.edu.javeriana.servired.identidad.aplicacion.puertos.RepositorioSesiones.AccesoRevocado;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lista de revocación de tokens de acceso por jti, para el gateway (ADR-26).
 *
 * <p>Interna: el gateway no la enruta y el puerto de Identidad no se publica. Como el JWKS,
 * no exige autenticación: solo contiene jti aleatorios de tokens que ya no sirven, sin datos
 * de las cuentas.
 */
@RestController
public class ControladorRevocacion {

    private final Autenticacion autenticacion;
    private final Clock reloj;

    public ControladorRevocacion(Autenticacion autenticacion, Clock reloj) {
        this.autenticacion = autenticacion;
        this.reloj = reloj;
    }

    /**
     * @param desde si viene, solo los revocados desde ese momento (sincronización incremental);
     *              sin él, todos los revocados que aún no expiran (carga inicial)
     */
    @GetMapping("/interno/v1/tokens-revocados")
    public ListaRevocacion revocados(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant desde) {
        Instant ahora = reloj.instant();
        return new ListaRevocacion(ahora, autenticacion.accesosRevocados(desde, Contexto.correlacion()));
    }

    /** @param generadaEn hora de Identidad al responder: el gateway la usa como próximo «desde» */
    public record ListaRevocacion(Instant generadaEn, List<AccesoRevocado> revocados) {
    }
}
