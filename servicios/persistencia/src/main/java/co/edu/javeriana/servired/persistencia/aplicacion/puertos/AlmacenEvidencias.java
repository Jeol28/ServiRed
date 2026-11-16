package co.edu.javeriana.servired.persistencia.aplicacion.puertos;

import java.util.Optional;

/** Almacenamiento de objetos para fotografías y evidencias (Supabase Storage o S3 compatible). */
public interface AlmacenEvidencias {

    void guardar(String clave, String tipoContenido, byte[] contenido);

    Optional<byte[]> leer(String clave);

    void borrar(String clave);
}
