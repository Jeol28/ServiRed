package co.edu.javeriana.servired.reportes.dominio;

/** El archivo no es una fotografía aceptable (tipo, tamaño o contenido). */
public class EvidenciaInvalida extends RuntimeException {

    public EvidenciaInvalida(String mensaje) {
        super(mensaje);
    }
}
