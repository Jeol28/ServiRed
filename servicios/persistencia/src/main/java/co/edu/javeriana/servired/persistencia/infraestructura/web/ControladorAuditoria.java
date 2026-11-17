package co.edu.javeriana.servired.persistencia.infraestructura.web;

import co.edu.javeriana.servired.persistencia.aplicacion.OperacionesAuditoria;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.Filtro;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.Pagina;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.RegistroNuevo;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.Verificacion;
import co.edu.javeriana.servired.persistencia.dominio.IdCorrelacion;
import co.edu.javeriana.servired.persistencia.dominio.ServicioCliente;
import co.edu.javeriana.servired.persistencia.infraestructura.web.Solicitudes.EventoDto;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Registro de auditoría (solo `auditoria`) y publicación de acciones auditables (los demás). */
@RestController
@RequestMapping("/interno/v1")
public class ControladorAuditoria {

    private final OperacionesAuditoria auditoria;
    private final Solicitudes solicitudes;

    public ControladorAuditoria(OperacionesAuditoria auditoria, Solicitudes solicitudes) {
        this.auditoria = auditoria;
        this.solicitudes = solicitudes;
    }

    @PostMapping("/auditoria/registros")
    public ResponseEntity<Map<String, Boolean>> registrar(
            @RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s,
            @Valid @RequestBody SolicitudRegistro r) {
        boolean nuevo = auditoria.registrar(s, new RegistroNuevo(r.idEvento(), r.tipo(), r.version(), r.origen(),
                r.correlationId(), r.categoria(), r.accion(), r.resultado(), r.actorId(), r.actorRol(), r.recursoTipo(),
                r.recursoId(), r.detalle() == null ? "{}" : r.detalle().toString(), r.ocurridoEn()));
        return ResponseEntity.status(nuevo ? HttpStatus.CREATED : HttpStatus.OK).body(Map.of("nuevo", nuevo));
    }

    @GetMapping("/auditoria/registros")
    public Pagina consultar(@RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s,
                            @RequestParam(required = false) String categoria,
                            @RequestParam(required = false) String tipo,
                            @RequestParam(required = false) String accion,
                            @RequestParam(required = false) UUID actorId,
                            @RequestParam(required = false) String recursoId,
                            @RequestParam(required = false) Instant desde,
                            @RequestParam(required = false) Instant hasta,
                            @RequestParam(defaultValue = "0") int pagina,
                            @RequestParam(defaultValue = "50") int tamano) {
        return auditoria.consultar(s, new Filtro(categoria, tipo, accion, actorId, recursoId, desde, hasta, pagina, tamano));
    }

    @GetMapping("/auditoria/integridad")
    public Verificacion verificar(@RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s) {
        return auditoria.verificar(s);
    }

    @PostMapping("/acciones-auditadas")
    public ResponseEntity<Void> publicar(@RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s,
                                         @RequestHeader(IdCorrelacion.CABECERA) String correlacion,
                                         @Valid @RequestBody EventoDto evento) {
        auditoria.publicarAccion(s, new IdCorrelacion(correlacion), solicitudes.evento(evento));
        return ResponseEntity.accepted().build();
    }

    public record SolicitudRegistro(@NotNull UUID idEvento, @NotBlank @Size(max = 100) String tipo, int version,
                                    @NotBlank @Size(max = 50) String origen, UUID correlationId,
                                    @NotNull @Pattern(regexp = "ACCESO|ADMINISTRACION|EXPORTACION|MODERACION|SEGURIDAD|CAMBIO_ESTADO|VALIDACION|REPORTE|ALERTA")
                                    String categoria,
                                    @NotBlank @Size(max = 100) String accion,
                                    @Pattern(regexp = "EXITO|FALLO") String resultado, UUID actorId,
                                    @Size(max = 30) String actorRol, @Size(max = 50) String recursoTipo,
                                    @Size(max = 100) String recursoId, JsonNode detalle, @NotNull Instant ocurridoEn) {
    }
}
