package co.edu.javeriana.servired.identidad.aplicacion;

import co.edu.javeriana.servired.identidad.dominio.Rol;
import java.util.Map;
import java.util.UUID;

/**
 * Acción que Auditoría debe registrar (RF-12, evento accion.auditada v1). Nunca
 * lleva correos, nombres, claves ni IP: solo identificadores de cuenta.
 */
public record AccionAuditada(String categoria, String accion, boolean exito, UUID actorId, Rol actorRol,
                             String recursoTipo, String recursoId, Map<String, Object> detalle) {

    public AccionAuditada {
        detalle = detalle == null ? Map.of() : Map.copyOf(detalle);
    }

    public static AccionAuditada acceso(String accion, boolean exito, UUID cuenta, Rol rol, Map<String, Object> detalle) {
        return new AccionAuditada("ACCESO", accion, exito, exito ? cuenta : null, exito ? rol : null,
                cuenta == null ? null : "CUENTA", cuenta == null ? null : cuenta.toString(), detalle);
    }

    public static AccionAuditada administracion(String accion, Solicitante actor, UUID cuenta, Map<String, Object> detalle) {
        return new AccionAuditada("ADMINISTRACION", accion, true, actor == null ? null : actor.idCuenta(),
                actor == null ? null : actor.rol(), "CUENTA", cuenta.toString(), detalle);
    }

    public static AccionAuditada seguridad(String accion, Map<String, Object> detalle) {
        return new AccionAuditada("SEGURIDAD", accion, false, null, null, "SESION", null, detalle);
    }
}
