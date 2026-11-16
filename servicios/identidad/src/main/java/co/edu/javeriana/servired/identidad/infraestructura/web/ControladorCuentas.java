package co.edu.javeriana.servired.identidad.infraestructura.web;

import co.edu.javeriana.servired.identidad.aplicacion.AdministracionCuentas;
import co.edu.javeriana.servired.identidad.dominio.Rol;
import co.edu.javeriana.servired.identidad.infraestructura.web.Dtos.CambiosAdministrador;
import co.edu.javeriana.servired.identidad.infraestructura.web.Dtos.CambiosPropios;
import co.edu.javeriana.servired.identidad.infraestructura.web.Dtos.CuentaDto;
import co.edu.javeriana.servired.identidad.infraestructura.web.Dtos.NuevaCuentaPersonal;
import co.edu.javeriana.servired.identidad.infraestructura.web.Dtos.PaginaDto;
import java.net.URI;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** RF-11: cuenta propia (cualquier rol) y administración de cuentas (administrador). */
@RestController
@RequestMapping("/api/v1/cuentas")
public class ControladorCuentas {

    private static final Logger log = LoggerFactory.getLogger(ControladorCuentas.class);

    private final AdministracionCuentas cuentas;

    public ControladorCuentas(AdministracionCuentas cuentas) {
        this.cuentas = cuentas;
    }

    // ------------------------------------------------------------ cuenta propia
    @GetMapping("/yo")
    public CuentaDto yo(@AuthenticationPrincipal Jwt jwt) {
        return CuentaDto.de(cuentas.consultarPropia(Contexto.solicitante(jwt), Contexto.correlacion()));
    }

    @PatchMapping("/yo")
    public CuentaDto actualizarYo(@AuthenticationPrincipal Jwt jwt, @RequestBody CambiosPropios c) {
        return CuentaDto.de(cuentas.actualizarPropia(Contexto.solicitante(jwt), c.nombre(), c.claveActual(),
                c.claveNueva(), Contexto.correlacion()));
    }

    @DeleteMapping("/yo")
    public ResponseEntity<Void> eliminarYo(@AuthenticationPrincipal Jwt jwt) {
        cuentas.eliminarPropia(Contexto.solicitante(jwt), Contexto.correlacion());
        log.atInfo().addKeyValue("idCuenta", jwt.getSubject()).log("cuenta suprimida por su titular");
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------ administrador
    @PostMapping
    public ResponseEntity<CuentaDto> crear(@AuthenticationPrincipal Jwt jwt, @RequestBody NuevaCuentaPersonal c) {
        var cuenta = cuentas.crear(Contexto.solicitante(jwt), c.correo(), c.clave(), c.nombre(), c.rol(),
                c.idEmpresa(), Contexto.correlacion());
        log.atInfo().addKeyValue("idCuenta", cuenta.idCuenta()).addKeyValue("rol", cuenta.rol())
                .addKeyValue("administrador", jwt.getSubject()).log("cuenta creada por un administrador");
        return ResponseEntity.created(URI.create("/api/v1/cuentas/" + cuenta.idCuenta())).body(CuentaDto.de(cuenta));
    }

    @GetMapping
    public PaginaDto listar(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Rol rol,
                            @RequestParam(defaultValue = "0") int pagina,
                            @RequestParam(defaultValue = "20") int tamano) {
        var p = cuentas.listar(Contexto.solicitante(jwt), rol, pagina, tamano, Contexto.correlacion());
        return new PaginaDto(p.elementos().stream().map(CuentaDto::de).toList(), p.total(), p.pagina(), p.tamano());
    }

    @GetMapping("/{idCuenta}")
    public CuentaDto consultar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID idCuenta) {
        return CuentaDto.de(cuentas.consultar(Contexto.solicitante(jwt), idCuenta, Contexto.correlacion()));
    }

    @PatchMapping("/{idCuenta}")
    public CuentaDto actualizar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID idCuenta,
                                @RequestBody CambiosAdministrador c) {
        var cuenta = cuentas.actualizar(Contexto.solicitante(jwt), idCuenta, c.nombre(), c.activa(), c.idEmpresa(),
                Contexto.correlacion());
        log.atInfo().addKeyValue("idCuenta", idCuenta).addKeyValue("administrador", jwt.getSubject())
                .log("cuenta actualizada por un administrador");
        return CuentaDto.de(cuenta);
    }

    @DeleteMapping("/{idCuenta}")
    public ResponseEntity<Void> eliminar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID idCuenta) {
        cuentas.eliminar(Contexto.solicitante(jwt), idCuenta, Contexto.correlacion());
        log.atInfo().addKeyValue("idCuenta", idCuenta).addKeyValue("administrador", jwt.getSubject())
                .log("cuenta eliminada por un administrador");
        return ResponseEntity.noContent().build();
    }
}
