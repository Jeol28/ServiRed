package co.edu.javeriana.servired.persistencia.aplicacion.puertos;

import java.util.function.Supplier;

/** Ejecuta un bloque en una única transacción de base de datos. */
public interface UnidadDeTrabajo {

    <T> T ejecutar(Supplier<T> bloque);
}
