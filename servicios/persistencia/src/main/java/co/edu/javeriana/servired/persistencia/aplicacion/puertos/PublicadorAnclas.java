package co.edu.javeriana.servired.persistencia.aplicacion.puertos;

import co.edu.javeriana.servired.persistencia.dominio.Auditoria.Ancla;
import java.util.List;

/** Lugar fuera del sistema donde se publican las anclas de la cadena de auditoría (ADR-31). */
public interface PublicadorAnclas {

    boolean configurado();

    /** Descripción del destino, p. ej. github:dueño/repo@rama:ruta. */
    String destino();

    /** Publica el ancla (idempotente por fecha) y devuelve una referencia verificable (p. ej. el commit). */
    String publicar(Ancla ancla);

    /** Las anclas tal como están publicadas afuera (la fuente de la verificación externa). */
    List<Ancla> publicadas();
}
