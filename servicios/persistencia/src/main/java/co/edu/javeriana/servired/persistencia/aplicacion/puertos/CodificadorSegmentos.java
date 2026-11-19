package co.edu.javeriana.servired.persistencia.aplicacion.puertos;

import co.edu.javeriana.servired.persistencia.dominio.Auditoria.RegistroArchivado;
import java.util.List;

/** Formato de los archivos de segmento de auditoría (JSONL comprimido, ADR-29). */
public interface CodificadorSegmentos {

    byte[] codificar(List<RegistroArchivado> registros);

    List<RegistroArchivado> decodificar(byte[] archivo);
}
