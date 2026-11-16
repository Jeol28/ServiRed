package co.edu.javeriana.servired.reportes.dominio;

import java.util.Set;

/**
 * Gestor de evidencia (RF-1, RNF-SEG5): solo fotografías JPEG o PNG, de hasta
 * 5 MB y 40 megapíxeles, máximo 3 por reporte. El tipo se determina por el
 * contenido (firma del archivo), nunca por la extensión ni por lo que declare
 * el cliente. Toda imagen se vuelve a codificar, lo que elimina EXIF y GPS.
 */
public final class PoliticaEvidencia {

    public static final long TAMANO_MAXIMO_BYTES = 5L * 1024 * 1024;
    public static final long PIXELES_MAXIMOS = 40_000_000L;
    public static final int LADO_MAXIMO = 10_000;
    public static final int MAXIMO_POR_REPORTE = 3;
    public static final Set<String> TIPOS = Set.of("image/jpeg", "image/png");

    private PoliticaEvidencia() {
    }

    public static void validarTamano(long bytes) {
        if (bytes <= 0) {
            throw new EvidenciaInvalida("El archivo está vacío");
        }
        if (bytes > TAMANO_MAXIMO_BYTES) {
            throw new EvidenciaInvalida("La fotografía supera 5 MB");
        }
    }

    public static void validarDimensiones(int ancho, int alto) {
        if (ancho <= 0 || alto <= 0 || ancho > LADO_MAXIMO || alto > LADO_MAXIMO
                || (long) ancho * alto > PIXELES_MAXIMOS) {
            throw new EvidenciaInvalida("Dimensiones de imagen no permitidas");
        }
    }

    public static void validarCupo(Reporte reporte) {
        if (reporte.evidencias().size() >= MAXIMO_POR_REPORTE) {
            throw new ReglaIncumplida("LIMITE_EVIDENCIAS", "El reporte ya tiene " + MAXIMO_POR_REPORTE + " fotografías");
        }
    }

    /** Detecta el tipo por la firma del archivo; null si no es JPEG ni PNG. */
    public static String tipoPorFirma(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return "image/png";
        }
        return null;
    }
}
