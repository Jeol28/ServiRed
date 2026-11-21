package co.edu.javeriana.servired.identidad.aplicacion.puertos;

import co.edu.javeriana.servired.identidad.dominio.Correo;
import co.edu.javeriana.servired.identidad.dominio.Cuenta;
import co.edu.javeriana.servired.identidad.dominio.IdCorrelacion;
import co.edu.javeriana.servired.identidad.dominio.NuevaCuenta;
import co.edu.javeriana.servired.identidad.dominio.Rol;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Operaciones de negocio de Persistencia para cuentas (guardarUsuario, consultarCuenta…). */
public interface RepositorioCuentas {

    /** @throws co.edu.javeriana.servired.identidad.dominio.Excepciones.CorreoYaRegistrado */
    Cuenta registrar(NuevaCuenta cuenta, String hashClave, IdCorrelacion correlacion);


    Optional<Credenciales> credenciales(Correo correo, IdCorrelacion correlacion);

    Optional<Cuenta> consultar(UUID idCuenta, IdCorrelacion correlacion);

    /** Las más recientes primero; `correo` es un fragmento (sin distinguir mayúsculas) o null. */
    PaginaCuentas listar(Rol rol, String correo, int pagina, int tamano, IdCorrelacion correlacion);

    Optional<Cuenta> actualizar(UUID idCuenta, Cambios cambios, IdCorrelacion correlacion);


    boolean eliminar(UUID idCuenta, IdCorrelacion correlacion);


    record Credenciales(UUID idCuenta, String hashClave, Rol rol, UUID idEmpresa, String nombre, boolean activa,
                        Instant creadaEn) {
    }

    /** Cambios parciales; null = sin cambio. */
    record Cambios(String nombre, Boolean activa, UUID idEmpresa, String hashClave) {
    }

    record PaginaCuentas(List<Cuenta> elementos, long total, int pagina, int tamano) {
    }
}
