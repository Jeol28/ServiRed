package co.edu.javeriana.servired.identidad.aplicacion;

import java.time.Instant;
import co.edu.javeriana.servired.identidad.aplicacion.puertos.CifradorClaves;
import co.edu.javeriana.servired.identidad.aplicacion.puertos.EmisorTokens;
import co.edu.javeriana.servired.identidad.aplicacion.puertos.EmisorTokens.TokenAcceso;
import co.edu.javeriana.servired.identidad.aplicacion.puertos.RepositorioCuentas;
import co.edu.javeriana.servired.identidad.aplicacion.puertos.RepositorioSesiones;
import co.edu.javeriana.servired.identidad.dominio.Correo;
import co.edu.javeriana.servired.identidad.dominio.Cuenta;
import co.edu.javeriana.servired.identidad.dominio.DatosInvalidos;
import co.edu.javeriana.servired.identidad.dominio.Excepciones.CredencialesInvalidas;
import co.edu.javeriana.servired.identidad.dominio.Excepciones.SesionInvalida;
import co.edu.javeriana.servired.identidad.dominio.IdCorrelacion;
import co.edu.javeriana.servired.identidad.dominio.NuevaCuenta;
import co.edu.javeriana.servired.identidad.dominio.Rol;
import co.edu.javeriana.servired.identidad.aplicacion.puertos.RegistroAcciones;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.UUID;

/**
 * Gestor de sesiones y tokens (SAD, componentes de Identidad): registro de
 * ciudadanos, inicio de sesión, renovación con rotación y cierre.
 */
public class Autenticacion {

    private final RepositorioCuentas cuentas;
    private final RepositorioSesiones sesiones;
    private final CifradorClaves cifrador;
    private final EmisorTokens emisor;
    private final Clock reloj;
    private final Duration duracionRefresco;
    private final RegistroAcciones acciones;
    /** Hash con el que se compara cuando el correo no existe, para igualar los tiempos de respuesta. */
    private final String hashFicticio;

    public Autenticacion(RepositorioCuentas cuentas, RepositorioSesiones sesiones, CifradorClaves cifrador,
                         EmisorTokens emisor, Clock reloj, Duration duracionRefresco) {
        this(cuentas, sesiones, cifrador, emisor, reloj, duracionRefresco, (a, c) -> { });
    }

    public Autenticacion(RepositorioCuentas cuentas, RepositorioSesiones sesiones, CifradorClaves cifrador,
                         EmisorTokens emisor, Clock reloj, Duration duracionRefresco, RegistroAcciones acciones) {
        this.acciones = acciones;
        this.cuentas = cuentas;
        this.sesiones = sesiones;
        this.cifrador = cifrador;
        this.emisor = emisor;
        this.reloj = reloj;
        this.duracionRefresco = duracionRefresco;
        this.hashFicticio = cifrador.cifrar(UUID.randomUUID().toString());
    }

    public Cuenta registrarCiudadano(String correo, String clave, String nombre, boolean aceptaTratamientoDatos,
                                     IdCorrelacion correlacion) {
        NuevaCuenta nueva = NuevaCuenta.ciudadano(correo, clave, nombre, aceptaTratamientoDatos, reloj.instant());
        return cuentas.registrar(nueva, cifrador.cifrar(clave),
                List.of(new AccionAuditada("ADMINISTRACION", "REGISTRO_CIUDADANO", true, nueva.idCuenta(), Rol.CIUDADANO,
                        "CUENTA", nueva.idCuenta().toString(), Map.of())), correlacion);
    }

    public Sesion iniciarSesion(String correo, String clave, IdCorrelacion correlacion) {
        Correo c;
        try {
            c = Correo.de(correo);
        } catch (DatosInvalidos e) {
            throw new CredencialesInvalidas();
        }
        var credenciales = cuentas.credenciales(c, correlacion);
        if (credenciales.isEmpty()) {
            cifrador.coincide(clave == null ? "" : clave, hashFicticio);
            auditar(AccionAuditada.acceso("INICIO_SESION_FALLIDO", false, null, null,
                    Map.of("motivo", "CUENTA_INEXISTENTE")), correlacion);
            throw new CredencialesInvalidas();
        }
        var cred = credenciales.get();
        if (clave == null || !cifrador.coincide(clave, cred.hashClave()) || !cred.activa()) {
            auditar(AccionAuditada.acceso("INICIO_SESION_FALLIDO", false, cred.idCuenta(), null,
                    Map.of("motivo", cred.activa() ? "CLAVE_INCORRECTA" : "CUENTA_INACTIVA")), correlacion);
            throw new CredencialesInvalidas();
        }
        var refresco = TokensRefresco.generar();
        var acceso = emisor.planear();
        sesiones.abrir(UUID.randomUUID(), cred.idCuenta(), refresco.hash(),
                reloj.instant().plus(duracionRefresco), acceso, correlacion);
        auditar(AccionAuditada.acceso("INICIO_SESION", true, cred.idCuenta(), cred.rol(), Map.of()), correlacion);
        return new Sesion(emisor.emitir(acceso, cred.idCuenta(), cred.rol(), cred.idEmpresa(), cred.creadaEn()), refresco.valor(),
                cred.idCuenta(), cred.rol(), cred.nombre(), cred.idEmpresa());
    }

    public Sesion renovar(String tokenRefresco, IdCorrelacion correlacion) {
        if (tokenRefresco == null || tokenRefresco.isBlank() || tokenRefresco.length() > 100) {
            throw new SesionInvalida();
        }
        var nuevo = TokensRefresco.generar();
        var acceso = emisor.planear();
        var rotacion = sesiones.rotar(TokensRefresco.hash(tokenRefresco), UUID.randomUUID(), nuevo.hash(),
                reloj.instant().plus(duracionRefresco), acceso, correlacion);
        if (rotacion.reutilizado()) {
            auditar(AccionAuditada.seguridad("REFRESCO_REUTILIZADO",
                    Map.of("consecuencia", "sesiones de la cuenta revocadas")), correlacion);
        }
        Cuenta cuenta = rotacion.cuenta().orElseThrow(SesionInvalida::new);
        return new Sesion(emisor.emitir(acceso, cuenta.idCuenta(), cuenta.rol(), cuenta.idEmpresa(), cuenta.creadaEn()), nuevo.valor(),
                cuenta.idCuenta(), cuenta.rol(), cuenta.nombre(), cuenta.idEmpresa());
    }

    /**
     * La auditoría de accesos es de mejor esfuerzo: si no se puede publicar, el
     * usuario no queda bloqueado (la falla se registra en el log del adaptador).
     */
    private void auditar(AccionAuditada accion, IdCorrelacion correlacion) {
        try {
            acciones.publicar(accion, correlacion);
        } catch (RuntimeException e) {
            // Intencional: RNF-DC2, una falla de auditoría no impide la operación principal.
        }
    }

    public void cerrarSesion(String tokenRefresco, IdCorrelacion correlacion) {
        if (tokenRefresco != null && !tokenRefresco.isBlank() && tokenRefresco.length() <= 100) {
            sesiones.cerrar(TokensRefresco.hash(tokenRefresco), correlacion);
        }
    }

    /** Lista de revocación para el gateway (ADR-26). */
    public List<RepositorioSesiones.AccesoRevocado> accesosRevocados(Instant desde, IdCorrelacion correlacion) {
        return sesiones.accesosRevocados(desde, correlacion);
    }

    public record Sesion(TokenAcceso acceso, String tokenRefresco, UUID idCuenta, Rol rol, String nombre,
                         UUID idEmpresa) {
    }
}
