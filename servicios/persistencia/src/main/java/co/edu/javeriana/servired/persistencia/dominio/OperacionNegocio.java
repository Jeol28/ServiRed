package co.edu.javeriana.servired.persistencia.dominio;

import java.util.EnumSet;
import java.util.Set;

/**
 * Operaciones de negocio que expone Persistencia y qué servicio puede invocar
 * cada una (mínimo privilegio, RNF-SEG3). No hay operaciones CRUD genéricas.
 */
public enum OperacionNegocio {
    GUARDAR_REPORTE("guardarReporte", EnumSet.of(ServicioCliente.REPORTES)),
    CONSULTAR_REPORTE("consultarReporte", EnumSet.of(ServicioCliente.REPORTES)),
    LISTAR_REPORTES_CIUDADANO("listarReportesDeCiudadano", EnumSet.of(ServicioCliente.REPORTES)),
    BUSCAR_REPORTES_CERCANOS("buscarReportesCercanos", EnumSet.of(ServicioCliente.REPORTES)),
    REGISTRAR_RESTABLECIMIENTO("registrarRestablecimiento", EnumSet.of(ServicioCliente.REPORTES)),
    REGISTRAR_VALIDACION("registrarValidacion", EnumSet.of(ServicioCliente.REPORTES)),
    GUARDAR_EVIDENCIA("guardarEvidencia", EnumSet.of(ServicioCliente.REPORTES)),
    OBTENER_EVIDENCIA("obtenerEvidencia", EnumSet.of(ServicioCliente.REPORTES)),
    GUARDAR_DENUNCIA("guardarDenuncia", EnumSet.of(ServicioCliente.REPORTES)),
    ABRIR_CASO("abrirCaso", EnumSet.of(ServicioCliente.ATENCION)),
    REGISTRAR_CUENTA("registrarCuenta", EnumSet.of(ServicioCliente.IDENTIDAD)),
    BUSCAR_CREDENCIALES("buscarCredenciales", EnumSet.of(ServicioCliente.IDENTIDAD)),
    CONSULTAR_CUENTA("consultarCuenta", EnumSet.of(ServicioCliente.IDENTIDAD)),
    LISTAR_CUENTAS("listarCuentas", EnumSet.of(ServicioCliente.IDENTIDAD)),
    ACTUALIZAR_CUENTA("actualizarCuenta", EnumSet.of(ServicioCliente.IDENTIDAD)),
    ELIMINAR_CUENTA("eliminarCuenta", EnumSet.of(ServicioCliente.IDENTIDAD)),
    ABRIR_SESION("abrirSesion", EnumSet.of(ServicioCliente.IDENTIDAD)),
    ROTAR_SESION("rotarSesion", EnumSet.of(ServicioCliente.IDENTIDAD)),
    CERRAR_SESION("cerrarSesion", EnumSet.of(ServicioCliente.IDENTIDAD)),
    RESOLVER_MUNICIPIO("resolverMunicipio",
            EnumSet.of(ServicioCliente.REPORTES, ServicioCliente.ATENCION, ServicioCliente.INDICADORES));

    private final String nombre;
    private final Set<ServicioCliente> autorizados;

    OperacionNegocio(String nombre, Set<ServicioCliente> autorizados) {
        this.nombre = nombre;
        this.autorizados = autorizados;
    }

    public String nombre() {
        return nombre;
    }

    public void autorizar(ServicioCliente servicio) {
        if (!autorizados.contains(servicio)) {
            throw new AccesoDenegado(servicio, this);
        }
    }
}
