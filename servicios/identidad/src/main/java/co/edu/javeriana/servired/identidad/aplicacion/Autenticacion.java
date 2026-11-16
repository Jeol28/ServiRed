package co.edu.javeriana.servired.identidad.aplicacion;

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
import java.time.Clock;
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
    /** Hash con el que se compara cuando el correo no existe, para igualar los tiempos de respuesta. */
    private final String hashFicticio;

    public Autenticacion(RepositorioCuentas cuentas, RepositorioSesiones sesiones, CifradorClaves cifrador,
                         EmisorTokens emisor, Clock reloj, Duration duracionRefresco) {
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
        return cuentas.registrar(nueva, cifrador.cifrar(clave), correlacion);
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
            throw new CredencialesInvalidas();
        }
        var cred = credenciales.get();
        if (clave == null || !cifrador.coincide(clave, cred.hashClave()) || !cred.activa()) {
            throw new CredencialesInvalidas();
        }
        var refresco = TokensRefresco.generar();
        sesiones.abrir(UUID.randomUUID(), cred.idCuenta(), refresco.hash(),
                reloj.instant().plus(duracionRefresco), correlacion);
        return new Sesion(emisor.emitir(cred.idCuenta(), cred.rol(), cred.idEmpresa()), refresco.valor(),
                cred.idCuenta(), cred.rol(), cred.nombre(), cred.idEmpresa());
    }

    public Sesion renovar(String tokenRefresco, IdCorrelacion correlacion) {
        if (tokenRefresco == null || tokenRefresco.isBlank() || tokenRefresco.length() > 100) {
            throw new SesionInvalida();
        }
        var nuevo = TokensRefresco.generar();
        Cuenta cuenta = sesiones.rotar(TokensRefresco.hash(tokenRefresco), UUID.randomUUID(), nuevo.hash(),
                        reloj.instant().plus(duracionRefresco), correlacion)
                .orElseThrow(SesionInvalida::new);
        return new Sesion(emisor.emitir(cuenta.idCuenta(), cuenta.rol(), cuenta.idEmpresa()), nuevo.valor(),
                cuenta.idCuenta(), cuenta.rol(), cuenta.nombre(), cuenta.idEmpresa());
    }

    public void cerrarSesion(String tokenRefresco, IdCorrelacion correlacion) {
        if (tokenRefresco != null && !tokenRefresco.isBlank() && tokenRefresco.length() <= 100) {
            sesiones.cerrar(TokensRefresco.hash(tokenRefresco), correlacion);
        }
    }

    public record Sesion(TokenAcceso acceso, String tokenRefresco, UUID idCuenta, Rol rol, String nombre,
                         UUID idEmpresa) {
    }
}
