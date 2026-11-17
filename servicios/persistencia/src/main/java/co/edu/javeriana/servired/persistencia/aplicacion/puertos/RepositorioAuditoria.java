package co.edu.javeriana.servired.persistencia.aplicacion.puertos;

import co.edu.javeriana.servired.persistencia.dominio.Auditoria.Filtro;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.Pagina;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.RegistroNuevo;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.Verificacion;

public interface RepositorioAuditoria {

    /** Encadena el registro al último (bajo bloqueo); false si el evento ya estaba registrado. */
    boolean registrar(RegistroNuevo r);

    Pagina consultar(Filtro f);

    Verificacion verificar();
}
