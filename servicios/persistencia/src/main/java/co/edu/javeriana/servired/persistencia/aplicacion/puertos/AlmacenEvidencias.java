package co.edu.javeriana.servired.persistencia.aplicacion.puertos;

import co.edu.javeriana.servired.persistencia.dominio.ObjetoAlmacenado;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** Almacenamiento de objetos para fotografías y evidencias (Supabase Storage o S3 compatible). */
public interface AlmacenEvidencias {

    void guardar(String clave, String tipoContenido, byte[] contenido);

    Optional<byte[]> leer(String clave);

    /** Compensación de mejor esfuerzo: si falla solo se registra (el objeto queda huérfano). */
    void borrar(String clave);

    /** Borra el objeto; a diferencia de {@link #borrar}, una falla se propaga. */
    void eliminar(String clave);

    /** Recorre los objetos con ese prefijo, de a una página por llamada. */
    void recorrer(String prefijo, Consumer<List<ObjetoAlmacenado>> porPagina);
}
