package co.edu.javeriana.servired.persistencia.infraestructura.web;

import co.edu.javeriana.servired.persistencia.aplicacion.GestionCuentas;
import co.edu.javeriana.servired.persistencia.aplicacion.GestionSesiones;
import co.edu.javeriana.servired.persistencia.dominio.Cuentas.CambiosCuenta;
import co.edu.javeriana.servired.persistencia.dominio.Cuentas.Credenciales;
import co.edu.javeriana.servired.persistencia.dominio.Cuentas.Cuenta;
import co.edu.javeriana.servired.persistencia.dominio.Cuentas.CuentaNueva;
import co.edu.javeriana.servired.persistencia.dominio.Cuentas.Pagina;
import co.edu.javeriana.servired.persistencia.dominio.Cuentas.Rotacion;
import co.edu.javeriana.servired.persistencia.dominio.Cuentas.SesionNueva;
import co.edu.javeriana.servired.persistencia.dominio.IdCorrelacion;
import co.edu.javeriana.servired.persistencia.dominio.ServicioCliente;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import co.edu.javeriana.servired.persistencia.infraestructura.web.Solicitudes.EventoDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operaciones de negocio del dominio de identidad (solo el servicio Identidad).
 * El correo viaja en el cuerpo, nunca en la URL, para que no quede en logs.
 */
@RestController
@RequestMapping("/interno/v1")
public class ControladorIdentidad {

    private static final String ROLES = "CIUDADANO|OPERADOR|REGULADOR|ADMINISTRADOR";

    private final GestionCuentas cuentas;
    private final GestionSesiones sesiones;
    private final Solicitudes solicitudes;

    public ControladorIdentidad(GestionCuentas cuentas, GestionSesiones sesiones, Solicitudes solicitudes) {
        this.cuentas = cuentas;
        this.sesiones = sesiones;
        this.solicitudes = solicitudes;
    }

    @PostMapping("/cuentas/registrar")
    public ResponseEntity<Cuenta> registrar(@RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s,
                                            @RequestHeader(IdCorrelacion.CABECERA) String correlacion,
                                            @Valid @RequestBody SolicitudRegistrar c) {
        var r = cuentas.registrar(s, new IdCorrelacion(correlacion), new CuentaNueva(c.idCuenta(), c.correo(),
                c.hashClave(), c.rol(), c.idEmpresa(), c.nombre(), c.consentimientoDatosEn(), c.creadaEn()),
                eventos(c.eventos()));
        return ResponseEntity.status(r.nueva() ? HttpStatus.CREATED : HttpStatus.OK).body(r.cuenta());
    }

    @PostMapping("/cuentas/credenciales")
    public ResponseEntity<Credenciales> credenciales(
            @RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s,
            @Valid @RequestBody SolicitudCredenciales c) {
        return ResponseEntity.of(cuentas.credenciales(s, c.correo()));
    }

    @GetMapping("/cuentas/{idCuenta}")
    public ResponseEntity<Cuenta> consultar(@RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s,
                                            @PathVariable UUID idCuenta) {
        return ResponseEntity.of(cuentas.consultar(s, idCuenta));
    }

    @GetMapping("/cuentas")
    public Pagina listar(@RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s,
                         @RequestParam(required = false) @Pattern(regexp = ROLES) String rol,
                         @RequestParam(defaultValue = "0") int pagina,
                         @RequestParam(defaultValue = "20") int tamano) {
        return cuentas.listar(s, rol, pagina, tamano);
    }

    @PostMapping("/cuentas/{idCuenta}/actualizar")
    public ResponseEntity<Cuenta> actualizar(@RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s,
                                             @RequestHeader(IdCorrelacion.CABECERA) String correlacion,
                                             @PathVariable UUID idCuenta, @Valid @RequestBody CambiosCuentaDto c) {
        return ResponseEntity.of(cuentas.actualizar(s, new IdCorrelacion(correlacion), idCuenta,
                new CambiosCuenta(c.nombre(), c.activa(), c.idEmpresa(), c.hashClave()), eventos(c.eventos())));
    }

    @PostMapping("/cuentas/{idCuenta}/eliminar")
    public ResponseEntity<Void> eliminar(@RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s,
                                         @RequestHeader(IdCorrelacion.CABECERA) String correlacion,
                                         @PathVariable UUID idCuenta,
                                         @RequestBody(required = false) SolicitudEliminar cuerpo) {
        return cuentas.eliminar(s, new IdCorrelacion(correlacion), idCuenta,
                eventos(cuerpo == null ? null : cuerpo.eventos()))
                ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/sesiones/abrir")
    public ResponseEntity<Void> abrirSesion(@RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s,
                                            @Valid @RequestBody SolicitudSesion c) {
        sesiones.abrir(s, new SesionNueva(c.idSesion(), c.idCuenta(), c.hashRefresco(), c.expiraEn()));
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/sesiones/rotar")
    public ResponseEntity<?> rotarSesion(@RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s,
                                         @Valid @RequestBody SolicitudRotar c) {
        var r = sesiones.rotar(s, new Rotacion(c.hashRefrescoActual(), c.idSesionNueva(), c.hashRefrescoNuevo(),
                c.expiraEn()));
        if (r.esValida()) {
            return ResponseEntity.ok(Map.of("cuenta", r.cuenta()));
        }
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "La sesión no es válida");
        p.setTitle("Sesión inválida");
        p.setProperty("motivo", r.motivo().name());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(p);
    }

    @PostMapping("/sesiones/cerrar")
    public ResponseEntity<Void> cerrarSesion(@RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s,
                                             @Valid @RequestBody SolicitudCerrar c) {
        sesiones.cerrar(s, c.hashRefresco());
        return ResponseEntity.noContent().build();
    }

    public record SolicitudRegistrar(@NotNull UUID idCuenta, @NotBlank @Size(max = 254) String correo,
                                     @NotBlank String hashClave, @NotNull @Pattern(regexp = ROLES) String rol,
                                     UUID idEmpresa, @Size(max = 120) String nombre, Instant consentimientoDatosEn,
                                     @NotNull Instant creadaEn, List<@Valid EventoDto> eventos) {
    }

    public record SolicitudEliminar(List<@Valid EventoDto> eventos) {
    }

    private List<co.edu.javeriana.servired.persistencia.dominio.EventoSaliente> eventos(List<EventoDto> dtos) {
        return dtos == null ? List.of() : dtos.stream().map(solicitudes::evento).toList();
    }

    public record SolicitudCredenciales(@NotBlank @Size(max = 254) String correo) {
    }

    public record CambiosCuentaDto(@Size(max = 120) String nombre, Boolean activa, UUID idEmpresa, String hashClave,
                                   List<@Valid EventoDto> eventos) {
    }

    public record SolicitudSesion(@NotNull UUID idSesion, @NotNull UUID idCuenta,
                                  @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String hashRefresco,
                                  @NotNull Instant expiraEn) {
    }

    public record SolicitudRotar(@NotBlank @Pattern(regexp = "[0-9a-f]{64}") String hashRefrescoActual,
                                 @NotNull UUID idSesionNueva,
                                 @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String hashRefrescoNuevo,
                                 @NotNull Instant expiraEn) {
    }

    public record SolicitudCerrar(@NotBlank @Pattern(regexp = "[0-9a-f]{64}") String hashRefresco) {
    }
}
