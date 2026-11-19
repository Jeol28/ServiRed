package co.edu.javeriana.servired.persistencia.dominio;

import java.time.Instant;

/** Un objeto del almacén de evidencias, tal como lo lista el almacén. */
public record ObjetoAlmacenado(String clave, Instant modificado) {
}
