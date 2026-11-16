package co.edu.javeriana.servired.identidad.infraestructura.web;

import co.edu.javeriana.servired.identidad.aplicacion.Autenticacion;
import co.edu.javeriana.servired.identidad.infraestructura.web.Dtos.CuentaDto;
import co.edu.javeriana.servired.identidad.infraestructura.web.Dtos.Login;
import co.edu.javeriana.servired.identidad.infraestructura.web.Dtos.Refresco;
import co.edu.javeriana.servired.identidad.infraestructura.web.Dtos.Registro;
import co.edu.javeriana.servired.identidad.infraestructura.web.Dtos.SesionDto;
import java.net.URI;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Autenticación pública: registro de ciudadanos, inicio, renovación y cierre de sesión. */
@RestController
@RequestMapping("/api/v1/auth")
public class ControladorAutenticacion {

    private static final Logger log = LoggerFactory.getLogger(ControladorAutenticacion.class);

    private final Autenticacion autenticacion;
    private final Clock reloj;

    public ControladorAutenticacion(Autenticacion autenticacion, Clock reloj) {
        this.autenticacion = autenticacion;
        this.reloj = reloj;
    }

    @PostMapping("/registro")
    public ResponseEntity<CuentaDto> registro(@RequestBody Registro r) {
        var cuenta = autenticacion.registrarCiudadano(r.correo(), r.clave(), r.nombre(),
                Boolean.TRUE.equals(r.aceptaTratamientoDatos()), Contexto.correlacion());
        log.atInfo().addKeyValue("idCuenta", cuenta.idCuenta()).log("ciudadano registrado");
        return ResponseEntity.created(URI.create("/api/v1/cuentas/yo")).body(CuentaDto.de(cuenta));
    }

    @PostMapping("/login")
    public ResponseEntity<SesionDto> login(@RequestBody Login l) {
        var sesion = autenticacion.iniciarSesion(l.correo(), l.clave(), Contexto.correlacion());
        log.atInfo().addKeyValue("idCuenta", sesion.idCuenta()).addKeyValue("rol", sesion.rol())
                .log("sesión iniciada");
        return sinCache(SesionDto.de(sesion, reloj));
    }

    @PostMapping("/refrescar")
    public ResponseEntity<SesionDto> refrescar(@RequestBody Refresco r) {
        return sinCache(SesionDto.de(autenticacion.renovar(r.tokenRefresco(), Contexto.correlacion()), reloj));
    }

    @PostMapping("/cerrar-sesion")
    public ResponseEntity<Void> cerrarSesion(@RequestBody Refresco r) {
        autenticacion.cerrarSesion(r.tokenRefresco(), Contexto.correlacion());
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<SesionDto> sinCache(SesionDto cuerpo) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(cuerpo);
    }
}
