package co.edu.javeriana.servired.identidad.aplicacion.puertos;

import co.edu.javeriana.servired.identidad.aplicacion.AccionAuditada;
import co.edu.javeriana.servired.identidad.dominio.Correo;
import co.edu.javeriana.servired.identidad.dominio.Cuenta;
import co.edu.javeriana.servired.identidad.dominio.IdCorrelacion;
import co.edu.javeriana.servired.identidad.dominio.NuevaCuenta;
import co.edu.javeriana.servired.identidad.dominio.Rol;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Operaciones de negocio de Persistencia para cuentas (guardarUsuario, consultarCuenta…). */
public interface RepositorioCuentas {

    /** @throws co.edu.javeriana.servired.identidad.dominio.Excepciones.CorreoYaRegistrado */
    Cuenta registrar(NuevaCuenta cuenta, String hashClave, IdCorrelacion correlacion);

    /** Con las acciones auditables guardadas en la misma transacción (RF-12). */
    default Cuenta registrar(NuevaCuenta cuenta, String hashClave, List<AccionAuditada> acciones,
                             IdCorrelacion correlacion) {
        return registrar(cuenta, hashClave, correlacion);
    }

    Optional<Credenciales> credenciales(Correo correo, IdCorrelacion correlacion);

    Optional<Cuenta> consultar(UUID idCuenta, IdCorrelacion correlacion);

    PaginaCuentas listar(Rol rol, int pagina, int tamano, IdCorrelacion correlacion);

    Optional<Cuenta> actualizar(UUID idCuenta, Cambios cambios, IdCorrelacion correlacion);

    default Optional<Cuenta> actualizar(UUID idCuenta, Cambios cambios, List<AccionAuditada> acciones,
                                        IdCorrelacion correlacion) {
        return actualizar(idCuenta, cambios, correlacion);
    }

    boolean eliminar(UUID idCuenta, IdCorrelacion correlacion);

    default boolean eliminar(UUID idCuenta, List<AccionAuditada> acciones, IdCorrelacion correlacion) {
        return eliminar(idCuenta, correlacion);
    }

    record Credenciales(UUID idCuenta, String hashClave, Rol rol, UUID idEmpresa, String nombre, boolean activa) {
    }

    /** Cambios parciales; null = sin cambio. */
    record Cambios(String nombre, Boolean activa, UUID idEmpresa, String hashClave) {
    }

    record PaginaCuentas(List<Cuenta> elementos, long total, int pagina, int tamano) {
    }
}
