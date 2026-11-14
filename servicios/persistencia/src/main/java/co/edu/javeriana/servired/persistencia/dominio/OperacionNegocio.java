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
    ABRIR_CASO("abrirCaso", EnumSet.of(ServicioCliente.ATENCION)),
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
