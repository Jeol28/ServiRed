package co.edu.javeriana.servired.reportes.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.javeriana.servired.reportes.dominio.EvidenciaInvalida;
import co.edu.javeriana.servired.reportes.infraestructura.imagenes.SaneadorImagenesImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class SaneadorImagenesTest {

    private final SaneadorImagenesImageIO saneador = new SaneadorImagenesImageIO();

    static byte[] imagen(String formato, int ancho, int alto) throws Exception {
        var img = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, 0xFF0000);
        var salida = new ByteArrayOutputStream();
        ImageIO.write(img, formato, salida);
        return salida.toByteArray();
    }

    /** Inserta un segmento APP1 Exif con texto de GPS justo después del SOI. */
    static byte[] conExif(byte[] jpeg) {
        byte[] carga = "Exif\0\0GPSLatitude=4.6287;Make=TelefonoDePrueba".getBytes(StandardCharsets.ISO_8859_1);
        int largo = carga.length + 2;
        var salida = new ByteArrayOutputStream();
        salida.write(jpeg, 0, 2);
        salida.write(0xFF);
        salida.write(0xE1);
        salida.write(largo >> 8);
        salida.write(largo & 0xFF);
        salida.write(carga, 0, carga.length);
        salida.write(jpeg, 2, jpeg.length - 2);
        return salida.toByteArray();
    }

    @Test
    void eliminaExifYGpsDeUnJpeg() throws Exception {
        byte[] original = conExif(imagen("jpg", 64, 48));
        assertThat(new String(original, StandardCharsets.ISO_8859_1)).contains("GPSLatitude");

        var limpia = saneador.sanear(original);

        String texto = new String(limpia.contenido(), StandardCharsets.ISO_8859_1);
        assertThat(texto).doesNotContain("Exif").doesNotContain("GPSLatitude").doesNotContain("TelefonoDePrueba");
        assertThat(limpia.tipoContenido()).isEqualTo("image/jpeg");
        assertThat(limpia.ancho()).isEqualTo(64);
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(limpia.contenido()))).isNotNull();
    }

    @Test
    void conservaPngComoPng() throws Exception {
        var limpia = saneador.sanear(imagen("png", 10, 10));

        assertThat(limpia.tipoContenido()).isEqualTo("image/png");
    }

    @Test
    void rechazaArchivosQueNoSonImagenes() {
        assertThatThrownBy(() -> saneador.sanear("<script>alert(1)</script>".getBytes()))
                .isInstanceOf(EvidenciaInvalida.class);
        byte[] falsoJpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};
        assertThatThrownBy(() -> saneador.sanear(falsoJpeg)).isInstanceOf(EvidenciaInvalida.class);
    }

    @Test
    void rechazaDimensionesExcesivasAntesDeDecodificar() throws Exception {
        assertThatThrownBy(() -> saneador.sanear(imagen("png", 10_001, 1))).isInstanceOf(EvidenciaInvalida.class);
    }
}
