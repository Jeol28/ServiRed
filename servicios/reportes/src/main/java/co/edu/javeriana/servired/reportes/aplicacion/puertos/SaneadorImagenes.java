package co.edu.javeriana.servired.reportes.aplicacion.puertos;

import co.edu.javeriana.servired.reportes.aplicacion.Vistas.ImagenSaneada;

/**
 * Decodifica y vuelve a codificar una fotografía: verifica que sea una imagen
 * real y elimina todos sus metadatos (EXIF, GPS, modelo del equipo).
 *
 * @throws co.edu.javeriana.servired.reportes.dominio.EvidenciaInvalida si no es aceptable
 */
public interface SaneadorImagenes {

    ImagenSaneada sanear(byte[] contenido);
}
