package co.edu.javeriana.servired.identidad.aplicacion.puertos;

import co.edu.javeriana.servired.identidad.aplicacion.AccionAuditada;
import co.edu.javeriana.servired.identidad.dominio.IdCorrelacion;

/** Publica una acción auditable que no acompaña a ninguna escritura (p. ej. un login fallido). */
public interface RegistroAcciones {

    void publicar(AccionAuditada accion, IdCorrelacion correlacion);
}
