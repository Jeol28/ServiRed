package co.edu.javeriana.servired.reportes.infraestructura.imagenes;

import co.edu.javeriana.servired.reportes.aplicacion.Vistas.ImagenSaneada;
import co.edu.javeriana.servired.reportes.aplicacion.puertos.SaneadorImagenes;
import co.edu.javeriana.servired.reportes.dominio.EvidenciaInvalida;
import co.edu.javeriana.servired.reportes.dominio.PoliticaEvidencia;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

/**
 * Sanea fotografías con ImageIO (sin dependencias nativas):
 * <ol>
 * <li>Identifica el formato por la firma del archivo (JPEG o PNG).</li>
 * <li>Lee las dimensiones de la cabecera ANTES de decodificar, para rechazar
 * "bombas" de descompresión.</li>
 * <li>Decodifica los píxeles y vuelve a codificar sin metadatos: así se
 * eliminan EXIF, GPS, modelo del equipo y cualquier contenido incrustado.</li>
 * </ol>
 * Consecuencia: se pierde la orientación EXIF; los clientes deben enviar la
 * foto ya orientada.
 */
public class SaneadorImagenesImageIO implements SaneadorImagenes {

    private static final float CALIDAD_JPEG = 0.85f;

    @Override
    public ImagenSaneada sanear(byte[] contenido) {
        PoliticaEvidencia.validarTamano(contenido == null ? 0 : contenido.length);
        String tipo = PoliticaEvidencia.tipoPorFirma(contenido);
        if (tipo == null) {
            throw new EvidenciaInvalida("Solo se aceptan fotografías JPEG o PNG");
        }
        BufferedImage imagen = leer(contenido, tipo);
        byte[] limpia = "image/png".equals(tipo) ? escribirPng(imagen) : escribirJpeg(imagen);
        return new ImagenSaneada(tipo, limpia, imagen.getWidth(), imagen.getHeight());
    }

    private static BufferedImage leer(byte[] contenido, String tipo) {
        String formato = "image/png".equals(tipo) ? "png" : "jpeg";
        try (ImageInputStream entrada = ImageIO.createImageInputStream(new ByteArrayInputStream(contenido))) {
            var lectores = ImageIO.getImageReadersByFormatName(formato);
            if (!lectores.hasNext()) {
                throw new EvidenciaInvalida("Formato no soportado");
            }
            ImageReader lector = lectores.next();
            try {
                lector.setInput(entrada, true, true);
                PoliticaEvidencia.validarDimensiones(lector.getWidth(0), lector.getHeight(0));
                BufferedImage imagen = lector.read(0);
                if (imagen == null) {
                    throw new EvidenciaInvalida("La imagen está dañada");
                }
                return imagen;
            } finally {
                lector.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof EvidenciaInvalida ei) {
                throw ei;
            }
            throw new EvidenciaInvalida("La imagen está dañada o no es válida");
        }
    }

    private static byte[] escribirJpeg(BufferedImage original) {
        // JPEG no admite transparencia: se compone sobre blanco en RGB.
        BufferedImage rgb = new BufferedImage(original.getWidth(), original.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(original, 0, 0, Color.WHITE, null);
        g.dispose();
        ImageWriter escritor = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (var salida = new ByteArrayOutputStream(); ImageOutputStream flujo = ImageIO.createImageOutputStream(salida)) {
            escritor.setOutput(flujo);
            ImageWriteParam parametros = escritor.getDefaultWriteParam();
            parametros.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parametros.setCompressionQuality(CALIDAD_JPEG);
            escritor.write(null, new IIOImage(rgb, null, null), parametros);
            flujo.flush();
            return salida.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo codificar la imagen", e);
        } finally {
            escritor.dispose();
        }
    }

    private static byte[] escribirPng(BufferedImage imagen) {
        try (var salida = new ByteArrayOutputStream()) {
            ImageIO.write(imagen, "png", salida);
            return salida.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo codificar la imagen", e);
        }
    }
}
