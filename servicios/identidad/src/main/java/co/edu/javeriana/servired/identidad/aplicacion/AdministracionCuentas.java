package co.edu.javeriana.servired.identidad.aplicacion;

import co.edu.javeriana.servired.identidad.aplicacion.puertos.CifradorClaves;
import co.edu.javeriana.servired.identidad.aplicacion.puertos.RepositorioCuentas;
import co.edu.javeriana.servired.identidad.aplicacion.puertos.RepositorioCuentas.Cambios;
import co.edu.javeriana.servired.identidad.aplicacion.puertos.RepositorioCuentas.PaginaCuentas;
import co.edu.javeriana.servired.identidad.dominio.Cuenta;
import co.edu.javeriana.servired.identidad.dominio.DatosInvalidos;
import co.edu.javeriana.servired.identidad.dominio.Excepciones.CredencialesInvalidas;
import co.edu.javeriana.servired.identidad.dominio.Excepciones.CuentaNoEncontrada;
import co.edu.javeriana.servired.identidad.dominio.Excepciones.OperacionNoPermitida;
import co.edu.javeriana.servired.identidad.dominio.IdCorrelacion;
import co.edu.javeriana.servired.identidad.dominio.NuevaCuenta;
import co.edu.javeriana.servired.identidad.dominio.PoliticaClave;
import co.edu.javeriana.servired.identidad.dominio.ReglasAdministracion;
import co.edu.javeriana.servired.identidad.dominio.Rol;
import java.time.Clock;
import java.util.UUID;

/**
 * Gestor de cuentas (RF-11): el administrador crea, edita y elimina cuentas de
 * cualquier rol; cada usuario consulta, edita y suprime la suya. El permiso por
 * rol lo exige la capa web; aquí se aplican las reglas que dependen de los datos.
 */
public class AdministracionCuentas {

    private final RepositorioCuentas cuentas;
    private final CifradorClaves cifrador;
    private final Clock reloj;

    public AdministracionCuentas(RepositorioCuentas cuentas, CifradorClaves cifrador, Clock reloj) {
        this.cuentas = cuentas;
        this.cifrador = cifrador;
        this.reloj = reloj;
    }

    // ------------------------------------------------------------ administrador
    public Cuenta crear(Solicitante admin, String correo, String clave, String nombre, Rol rol, UUID idEmpresa,
                        IdCorrelacion correlacion) {
        exigirAdministrador(admin);
        NuevaCuenta nueva = NuevaCuenta.personal(correo, clave, nombre, rol, idEmpresa, reloj.instant());
        return cuentas.registrar(nueva, cifrador.cifrar(clave), correlacion);
    }

    public PaginaCuentas listar(Solicitante admin, Rol rol, int pagina, int tamano, IdCorrelacion correlacion) {
        exigirAdministrador(admin);
        return cuentas.listar(rol, pagina, tamano, correlacion);
    }

    public Cuenta consultar(Solicitante admin, UUID idCuenta, IdCorrelacion correlacion) {
        exigirAdministrador(admin);
        return cuentas.consultar(idCuenta, correlacion).orElseThrow(CuentaNoEncontrada::new);
    }

    public Cuenta actualizar(Solicitante admin, UUID idCuenta, String nombre, Boolean activa, UUID idEmpresa,
                             IdCorrelacion correlacion) {
        exigirAdministrador(admin);
        ReglasAdministracion.validarCambioDeOtraCuenta(admin.idCuenta(), idCuenta, activa, false);
        ReglasAdministracion.validarNombre(nombre);
        if (idEmpresa != null) {
            Cuenta actual = consultar(admin, idCuenta, correlacion);
            if (!actual.rol().requiereEmpresa()) {
                throw new DatosInvalidos("idEmpresa", "solo aplica a operadores");
            }
        }
        return cuentas.actualizar(idCuenta, new Cambios(limpiar(nombre), activa, idEmpresa, null), correlacion)
                .orElseThrow(CuentaNoEncontrada::new);
    }

    public void eliminar(Solicitante admin, UUID idCuenta, IdCorrelacion correlacion) {
        exigirAdministrador(admin);
        ReglasAdministracion.validarCambioDeOtraCuenta(admin.idCuenta(), idCuenta, null, true);
        if (!cuentas.eliminar(idCuenta, correlacion)) {
            throw new CuentaNoEncontrada();
        }
    }

    // ------------------------------------------------------------ cuenta propia
    public Cuenta consultarPropia(Solicitante yo, IdCorrelacion correlacion) {
        return cuentas.consultar(yo.idCuenta(), correlacion).orElseThrow(CuentaNoEncontrada::new);
    }

    /** Cambiar la clave exige la clave actual y cierra todas las sesiones. */
    public Cuenta actualizarPropia(Solicitante yo, String nombre, String claveActual, String claveNueva,
                                   IdCorrelacion correlacion) {
        ReglasAdministracion.validarNombre(nombre);
        String hashNuevo = null;
        if (claveNueva != null) {
            Cuenta actual = consultarPropia(yo, correlacion);
            var cred = cuentas.credenciales(actual.correo(), correlacion).orElseThrow(CuentaNoEncontrada::new);
            if (claveActual == null || !cifrador.coincide(claveActual, cred.hashClave())) {
                throw new CredencialesInvalidas();
            }
            PoliticaClave.validar(claveNueva, actual.correo());
            hashNuevo = cifrador.cifrar(claveNueva);
        }
        return cuentas.actualizar(yo.idCuenta(), new Cambios(limpiar(nombre), null, null, hashNuevo), correlacion)
                .orElseThrow(CuentaNoEncontrada::new);
    }

    /** Derecho de supresión (Ley 1581): los datos personales se anonimizan. */
    public void eliminarPropia(Solicitante yo, IdCorrelacion correlacion) {
        ReglasAdministracion.validarSupresionPropia(yo.rol());
        if (!cuentas.eliminar(yo.idCuenta(), correlacion)) {
            throw new CuentaNoEncontrada();
        }
    }

    private static void exigirAdministrador(Solicitante s) {
        if (s.rol() != Rol.ADMINISTRADOR) {
            throw new OperacionNoPermitida("Requiere el rol administrador");
        }
    }

    private static String limpiar(String nombre) {
        return nombre == null || nombre.isBlank() ? null : nombre.strip();
    }
}
