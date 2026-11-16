package co.edu.javeriana.servired.persistencia.aplicacion;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioCuentas;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioSesiones;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.UnidadDeTrabajo;
import co.edu.javeriana.servired.persistencia.dominio.Cuentas.MotivoRechazo;
import co.edu.javeriana.servired.persistencia.dominio.Cuentas.ResultadoRotacion;
import co.edu.javeriana.servired.persistencia.dominio.Cuentas.Rotacion;
import co.edu.javeriana.servired.persistencia.dominio.Cuentas.SesionNueva;
import co.edu.javeriana.servired.persistencia.dominio.OperacionNegocio;
import co.edu.javeriana.servired.persistencia.dominio.ServicioCliente;
import java.time.Clock;
import java.util.UUID;

/**
 * Sesiones de renovación con rotación: cada token de refresco sirve una sola
 * vez. Presentar uno ya rotado se trata como robo y revoca todas las sesiones
 * de la cuenta (detección de reutilización).
 */
public class GestionSesiones {

    private final UnidadDeTrabajo transaccion;
    private final RepositorioSesiones sesiones;
    private final RepositorioCuentas cuentas;
    private final Clock reloj;

    public GestionSesiones(UnidadDeTrabajo transaccion, RepositorioSesiones sesiones, RepositorioCuentas cuentas,
                           Clock reloj) {
        this.transaccion = transaccion;
        this.sesiones = sesiones;
        this.cuentas = cuentas;
        this.reloj = reloj;
    }

    public void abrir(ServicioCliente servicio, SesionNueva sesion) {
        OperacionNegocio.ABRIR_SESION.autorizar(servicio);
        transaccion.ejecutar(() -> {
            sesiones.insertar(sesion);
            return null;
        });
    }

    public ResultadoRotacion rotar(ServicioCliente servicio, Rotacion rotacion) {
        OperacionNegocio.ROTAR_SESION.autorizar(servicio);
        return transaccion.ejecutar(() -> {
            var actual = sesiones.bloquearPorHash(rotacion.hashRefrescoActual());
            if (actual.isEmpty()) {
                return ResultadoRotacion.rechazada(MotivoRechazo.INEXISTENTE);
            }
            var sesion = actual.get();
            if (sesion.revocada()) {
                sesiones.revocarTodas(sesion.idCuenta());
                return ResultadoRotacion.rechazada(MotivoRechazo.REUTILIZADA);
            }
            if (!sesion.expiraEn().isAfter(reloj.instant())) {
                return ResultadoRotacion.rechazada(MotivoRechazo.EXPIRADA);
            }
            var cuenta = cuentas.buscar(sesion.idCuenta()).filter(c -> c.activa());
            if (cuenta.isEmpty()) {
                sesiones.revocarTodas(sesion.idCuenta());
                return ResultadoRotacion.rechazada(MotivoRechazo.CUENTA_INACTIVA);
            }
            UUID nueva = rotacion.idSesionNueva();
            sesiones.insertar(new SesionNueva(nueva, sesion.idCuenta(), rotacion.hashRefrescoNuevo(),
                    rotacion.expiraEn()));
            sesiones.revocar(sesion.idSesion(), nueva);
            return ResultadoRotacion.valida(cuenta.get());
        });
    }

    public void cerrar(ServicioCliente servicio, String hashRefresco) {
        OperacionNegocio.CERRAR_SESION.autorizar(servicio);
        transaccion.ejecutar(() -> {
            sesiones.bloquearPorHash(hashRefresco)
                    .filter(s -> !s.revocada())
                    .ifPresent(s -> sesiones.revocar(s.idSesion(), null));
            return null;
        });
    }
}
