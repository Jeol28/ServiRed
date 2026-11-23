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
    OBTENER_EVIDENCIA_CASO("obtenerEvidenciaDeCaso", EnumSet.of(ServicioCliente.ATENCION)),
    APLICAR_ESTADO_DE_CASO("aplicarEstadoDeCaso", EnumSet.of(ServicioCliente.REPORTES)),
    GUARDAR_DENUNCIA("guardarDenuncia", EnumSet.of(ServicioCliente.REPORTES)),
    ABRIR_CASO("abrirCaso", EnumSet.of(ServicioCliente.ATENCION)),
    BUSCAR_COBERTURA("buscarCobertura", EnumSet.of(ServicioCliente.ATENCION)),
    CONSULTAR_CASO("consultarCaso", EnumSet.of(ServicioCliente.ATENCION)),
    LISTAR_CASOS("listarCasos", EnumSet.of(ServicioCliente.ATENCION)),
    ACTUALIZAR_CASO("actualizarCaso", EnumSet.of(ServicioCliente.ATENCION)),
    VERIFICAR_OPERADOR("verificarOperador", EnumSet.of(ServicioCliente.ATENCION)),
    ABRIR_MODERACION("abrirModeracion", EnumSet.of(ServicioCliente.ATENCION)),
    CONSULTAR_MODERACION("consultarModeracion", EnumSet.of(ServicioCliente.ATENCION)),
    DECIDIR_MODERACION("decidirModeracion", EnumSet.of(ServicioCliente.ATENCION)),
    OBTENER_EVIDENCIA_DENUNCIA("obtenerEvidenciaDenuncia", EnumSet.of(ServicioCliente.ATENCION)),
    REGISTRAR_CUENTA("registrarCuenta", EnumSet.of(ServicioCliente.IDENTIDAD)),
    BUSCAR_CREDENCIALES("buscarCredenciales", EnumSet.of(ServicioCliente.IDENTIDAD)),
    CONSULTAR_CUENTA("consultarCuenta", EnumSet.of(ServicioCliente.IDENTIDAD)),
    LISTAR_CUENTAS("listarCuentas", EnumSet.of(ServicioCliente.IDENTIDAD)),
    ACTUALIZAR_CUENTA("actualizarCuenta", EnumSet.of(ServicioCliente.IDENTIDAD)),
    ELIMINAR_CUENTA("eliminarCuenta", EnumSet.of(ServicioCliente.IDENTIDAD)),
    ABRIR_SESION("abrirSesion", EnumSet.of(ServicioCliente.IDENTIDAD)),
    ROTAR_SESION("rotarSesion", EnumSet.of(ServicioCliente.IDENTIDAD)),
    CERRAR_SESION("cerrarSesion", EnumSet.of(ServicioCliente.IDENTIDAD)),
    LISTAR_ACCESOS_REVOCADOS("listarAccesosRevocados", EnumSet.of(ServicioCliente.IDENTIDAD)),
    ACTUALIZAR_VISTA("actualizarVistaReporte", EnumSet.of(ServicioCliente.INDICADORES)),
    CALCULAR_AGREGADOS("calcularAgregados", EnumSet.of(ServicioCliente.INDICADORES)),
    LISTAR_HECHOS("listarHechos", EnumSet.of(ServicioCliente.INDICADORES)),
    CONSULTAR_ESTADO_VISTA("consultarEstadoVista", EnumSet.of(ServicioCliente.INDICADORES)),
    RECONSTRUIR_VISTA("reconstruirVistaIndicadores", EnumSet.of(ServicioCliente.INDICADORES)),
    REGISTRAR_AUDITORIA("registrarAuditoria", EnumSet.of(ServicioCliente.AUDITORIA)),
    CONSULTAR_AUDITORIA("consultarAuditoria", EnumSet.of(ServicioCliente.AUDITORIA)),
    VERIFICAR_AUDITORIA("verificarIntegridadAuditoria", EnumSet.of(ServicioCliente.AUDITORIA)),
    ARCHIVAR_AUDITORIA("archivarAuditoria", EnumSet.of(ServicioCliente.AUDITORIA)),
    ANCLAR_AUDITORIA("anclarAuditoria", EnumSet.of(ServicioCliente.AUDITORIA)),
    PUBLICAR_ACCION_AUDITADA("publicarAccionAuditada", EnumSet.of(ServicioCliente.IDENTIDAD, ServicioCliente.REPORTES,
            ServicioCliente.ATENCION, ServicioCliente.INDICADORES)),
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
