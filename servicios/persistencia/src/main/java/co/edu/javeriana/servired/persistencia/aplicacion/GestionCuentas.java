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
    private final co.edu.javeriana.servired.persistencia.aplicacion.puertos.Outbox outbox;

    public GestionCuentas(UnidadDeTrabajo transaccion, RegistroIdempotencia idempotencia,
                          RepositorioCuentas cuentas, RepositorioSesiones sesiones,
                          co.edu.javeriana.servired.persistencia.aplicacion.puertos.Outbox outbox) {
        this.transaccion = transaccion;
        this.idempotencia = idempotencia;
        this.cuentas = cuentas;
        this.sesiones = sesiones;
        this.outbox = outbox;
    }

    /** Eventos accion.auditada (RF-12) que viajan con la operación y se guardan en su misma transacción. */
    private static void validar(ServicioCliente s, IdCorrelacion c, java.util.List<EventoSaliente> eventos) {
        eventos.forEach(e -> e.validarPara(s, c, java.util.Set.of("accion.auditada")));
    }

    /** Idempotente por X-Correlation-Id; un correo ya registrado se rechaza (409). */
    public Resultado registrar(ServicioCliente servicio, IdCorrelacion correlacion, CuentaNueva cuenta) {
        return registrar(servicio, correlacion, cuenta, java.util.List.of());
    }

    public Resultado registrar(ServicioCliente servicio, IdCorrelacion correlacion, CuentaNueva cuenta,
                               java.util.List<EventoSaliente> eventos) {
        OperacionNegocio.REGISTRAR_CUENTA.autorizar(servicio);
        validar(servicio, correlacion, eventos);
        return transaccion.ejecutar(() -> {
            var previo = idempotencia.reclamar(correlacion, OperacionNegocio.REGISTRAR_CUENTA, servicio);
            if (previo.isPresent()) {
                return new Resultado(cargar(previo.get()), false);
            }
            if (cuentas.existeCorreo(cuenta.correo())) {
                throw new SolicitudRechazada("El correo ya está registrado");
            }
            cuentas.insertar(cuenta);
            eventos.forEach(outbox::agregar);
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
        return actualizar(servicio, IdCorrelacion.nuevo(), idCuenta, cambios, java.util.List.of());
    }

    public Optional<Cuenta> actualizar(ServicioCliente servicio, IdCorrelacion correlacion, UUID idCuenta,
                                       CambiosCuenta cambios, java.util.List<EventoSaliente> eventos) {
        OperacionNegocio.ACTUALIZAR_CUENTA.autorizar(servicio);
        validar(servicio, correlacion, eventos);
        return transaccion.ejecutar(() -> {
            if (!cuentas.actualizar(idCuenta, cambios)) {
                return Optional.empty();
            }
            eventos.forEach(outbox::agregar);
            if (cambios.invalidaSesiones()) {
                sesiones.revocarTodas(idCuenta);
            }
            return cuentas.buscar(idCuenta);
        });
    }

    /** Derecho de supresión: anonimiza los datos personales y revoca las sesiones. */
    public boolean eliminar(ServicioCliente servicio, UUID idCuenta) {
        return eliminar(servicio, IdCorrelacion.nuevo(), idCuenta, java.util.List.of());
    }

    public boolean eliminar(ServicioCliente servicio, IdCorrelacion correlacion, UUID idCuenta,
                            java.util.List<EventoSaliente> eventos) {
        OperacionNegocio.ELIMINAR_CUENTA.autorizar(servicio);
        validar(servicio, correlacion, eventos);
        return transaccion.ejecutar(() -> {
            if (!cuentas.anonimizar(idCuenta)) {
                return false;
            }
            eventos.forEach(outbox::agregar);
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
