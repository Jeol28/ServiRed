package co.edu.javeriana.servired.persistencia.aplicacion;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RegistroIdempotencia;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioCuentas;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioSesiones;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.UnidadDeTrabajo;
import co.edu.javeriana.servired.persistencia.dominio.Cuentas.CambiosCuenta;
import co.edu.javeriana.servired.persistencia.dominio.Cuentas.Credenciales;
import co.edu.javeriana.servired.persistencia.dominio.Cuentas.Cuenta;
import co.edu.javeriana.servired.persistencia.dominio.Cuentas.CuentaNueva;
import co.edu.javeriana.servired.persistencia.dominio.Cuentas.Pagina;
import co.edu.javeriana.servired.persistencia.dominio.EventoSaliente;
import co.edu.javeriana.servired.persistencia.dominio.IdCorrelacion;
import co.edu.javeriana.servired.persistencia.dominio.OperacionNegocio;
import co.edu.javeriana.servired.persistencia.dominio.ServicioCliente;
import co.edu.javeriana.servired.persistencia.dominio.SolicitudRechazada;
import java.util.Optional;
import java.util.UUID;

/** Operaciones de cuentas (RF-11) invocadas por Identidad. */
public class GestionCuentas {

    private final UnidadDeTrabajo transaccion;
    private final RegistroIdempotencia idempotencia;
    private final RepositorioCuentas cuentas;
    private final RepositorioSesiones sesiones;

    public GestionCuentas(UnidadDeTrabajo transaccion, RegistroIdempotencia idempotencia,
                          RepositorioCuentas cuentas, RepositorioSesiones sesiones) {
        this.transaccion = transaccion;
        this.idempotencia = idempotencia;
        this.cuentas = cuentas;
        this.sesiones = sesiones;
    }

    /** Idempotente por X-Correlation-Id; un correo ya registrado se rechaza (409). */
    public Resultado registrar(ServicioCliente servicio, IdCorrelacion correlacion, CuentaNueva cuenta) {
        OperacionNegocio.REGISTRAR_CUENTA.autorizar(servicio);
        return transaccion.ejecutar(() -> {
            // Sin cuenta todavía: el titular es el correo (se guarda solo su huella).
            var previo = idempotencia.reclamar(correlacion, OperacionNegocio.REGISTRAR_CUENTA, servicio,
                    "correo:" + cuenta.correo().toLowerCase(java.util.Locale.ROOT));
            if (previo.isPresent()) {
                return new Resultado(cargar(previo.get()), false);
            }
            if (cuentas.existeCorreo(cuenta.correo())) {
                throw new SolicitudRechazada("El correo ya está registrado");
            }
            cuentas.insertar(cuenta);
            idempotencia.registrarResultado(correlacion, OperacionNegocio.REGISTRAR_CUENTA, cuenta.idCuenta());
            return new Resultado(cargar(cuenta.idCuenta()), true);
        });
    }

    public Optional<Credenciales> credenciales(ServicioCliente servicio, String correo) {
        OperacionNegocio.BUSCAR_CREDENCIALES.autorizar(servicio);
        return cuentas.credencialesPorCorreo(correo);
    }

    public Optional<Cuenta> consultar(ServicioCliente servicio, UUID idCuenta) {
        OperacionNegocio.CONSULTAR_CUENTA.autorizar(servicio);
        return cuentas.buscar(idCuenta);
    }

    public Pagina listar(ServicioCliente servicio, String rol, String correo, int pagina, int tamano) {
        OperacionNegocio.LISTAR_CUENTAS.autorizar(servicio);
        String buscado = correo == null || correo.isBlank() ? null : correo.strip();
        return cuentas.listar(rol, buscado, Math.max(pagina, 0), Math.clamp(tamano, 1, 100));
    }

    /** Desactivar la cuenta o cambiar su clave revoca todas sus sesiones. */
    public Optional<Cuenta> actualizar(ServicioCliente servicio, UUID idCuenta, CambiosCuenta cambios) {
        OperacionNegocio.ACTUALIZAR_CUENTA.autorizar(servicio);
        return transaccion.ejecutar(() -> {
            if (!cuentas.actualizar(idCuenta, cambios)) {
                return Optional.empty();
            }
            if (cambios.invalidaSesiones()) {
                sesiones.revocarTodas(idCuenta);
            }
            return cuentas.buscar(idCuenta);
        });
    }

    /** Derecho de supresión: anonimiza los datos personales y revoca las sesiones. */
    public boolean eliminar(ServicioCliente servicio, UUID idCuenta) {
        OperacionNegocio.ELIMINAR_CUENTA.autorizar(servicio);
        return transaccion.ejecutar(() -> {
            if (!cuentas.anonimizar(idCuenta)) {
                return false;
            }
            sesiones.revocarTodas(idCuenta);
            return true;
        });
    }

    private Cuenta cargar(UUID id) {
        return cuentas.buscar(id).orElseThrow(() -> new IllegalStateException("Cuenta no encontrada: " + id));
    }

    public record Resultado(Cuenta cuenta, boolean nueva) {
    }
}
