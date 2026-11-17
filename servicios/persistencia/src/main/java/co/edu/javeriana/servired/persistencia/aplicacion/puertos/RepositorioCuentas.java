package co.edu.javeriana.servired.persistencia.aplicacion.puertos;

import co.edu.javeriana.servired.persistencia.dominio.Cuentas.CambiosCuenta;
import co.edu.javeriana.servired.persistencia.dominio.Cuentas.Credenciales;
import co.edu.javeriana.servired.persistencia.dominio.Cuentas.Cuenta;
import co.edu.javeriana.servired.persistencia.dominio.Cuentas.CuentaNueva;
import co.edu.javeriana.servired.persistencia.dominio.Cuentas.Pagina;
import java.util.Optional;
import java.util.UUID;

public interface RepositorioCuentas {

    boolean existeCorreo(String correo);

    void insertar(CuentaNueva cuenta);

    Optional<Credenciales> credencialesPorCorreo(String correo);

    Optional<Cuenta> buscar(UUID idCuenta);

    /**
     * Cuentas vigentes, las más recientes primero.
     * @param correo fragmento del correo (sin distinguir mayúsculas) o null
     */
    Pagina listar(String rol, String correo, int pagina, int tamano);

    /** @return false si la cuenta no existe o está eliminada */
    boolean actualizar(UUID idCuenta, CambiosCuenta cambios);

    /** Supresión: anonimiza correo y nombre e inhabilita la cuenta. */
    boolean anonimizar(UUID idCuenta);
}
