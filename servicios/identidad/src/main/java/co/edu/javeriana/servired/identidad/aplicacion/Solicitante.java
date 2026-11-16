package co.edu.javeriana.servired.identidad.aplicacion;

import co.edu.javeriana.servired.identidad.dominio.Rol;
import java.util.UUID;

/** Usuario autenticado que hace la petición (tomado del JWT ya validado). */
public record Solicitante(UUID idCuenta, Rol rol) {
}
