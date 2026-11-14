package co.edu.javeriana.servired.reportes.dominio;

/** Coordenadas WGS84 dentro del rectángulo que contiene a Colombia (incluye San Andrés). */
public record Ubicacion(double latitud, double longitud) {

    public static final double LATITUD_MIN = -4.3;
    public static final double LATITUD_MAX = 13.5;
    public static final double LONGITUD_MIN = -82;
    public static final double LONGITUD_MAX = -66.8;

    public boolean estaEnColombia() {
        return latitud >= LATITUD_MIN && latitud <= LATITUD_MAX
                && longitud >= LONGITUD_MIN && longitud <= LONGITUD_MAX;
    }
}
