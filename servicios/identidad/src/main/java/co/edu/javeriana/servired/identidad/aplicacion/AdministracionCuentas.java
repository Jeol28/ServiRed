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
import java.util.List;
import java.util.Map;
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
        var detalle = new java.util.HashMap<String, Object>(Map.of("rol", rol.name()));
        if (idEmpresa != null) {
            detalle.put("idEmpresa", idEmpresa.toString());
        }
        return cuentas.registrar(nueva, cifrador.cifrar(clave),
                List.of(AccionAuditada.administracion("CUENTA_CREADA", admin, nueva.idCuenta(), detalle)), correlacion);
    }

    public PaginaCuentas listar(Solicitante admin, Rol rol, String correo, int pagina, int tamano, IdCorrelacion correlacion) {
        exigirAdministrador(admin);
        String buscado = correo == null || correo.isBlank() ? null : correo.strip();
        if (buscado != null && buscado.length() > 254) buscado = buscado.substring(0, 254);
        return cuentas.listar(rol, buscado, pagina, tamano, correlacion);
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
        var cambios = new java.util.ArrayList<String>();
        if (nombre != null) cambios.add("nombre");
        if (activa != null) cambios.add(activa ? "activada" : "desactivada");
        if (idEmpresa != null) cambios.add("empresa");
        return cuentas.actualizar(idCuenta, new Cambios(limpiar(nombre), activa, idEmpresa, null),
                        List.of(AccionAuditada.administracion("CUENTA_ACTUALIZADA", admin, idCuenta,
                                Map.of("cambios", List.copyOf(cambios)))), correlacion)
                .orElseThrow(CuentaNoEncontrada::new);
    }

    public void eliminar(Solicitante admin, UUID idCuenta, IdCorrelacion correlacion) {
        exigirAdministrador(admin);
        ReglasAdministracion.validarCambioDeOtraCuenta(admin.idCuenta(), idCuenta, null, true);
        if (!cuentas.eliminar(idCuenta, List.of(AccionAuditada.administracion("CUENTA_ELIMINADA", admin, idCuenta,
                Map.of())), correlacion)) {
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
        List<AccionAuditada> acciones = hashNuevo == null ? List.of()
                : List.of(AccionAuditada.administracion("CLAVE_CAMBIADA", yo, yo.idCuenta(),
                        Map.of("consecuencia", "sesiones revocadas")));
        return cuentas.actualizar(yo.idCuenta(), new Cambios(limpiar(nombre), null, null, hashNuevo), acciones,
                        correlacion)
                .orElseThrow(CuentaNoEncontrada::new);
    }

    /** Derecho de supresión (Ley 1581): los datos personales se anonimizan. */
    public void eliminarPropia(Solicitante yo, IdCorrelacion correlacion) {
        ReglasAdministracion.validarSupresionPropia(yo.rol());
        if (!cuentas.eliminar(yo.idCuenta(), List.of(AccionAuditada.administracion("CUENTA_SUPRIMIDA", yo,
                yo.idCuenta(), Map.of())), correlacion)) {
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
