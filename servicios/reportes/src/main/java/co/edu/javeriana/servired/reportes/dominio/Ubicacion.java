package co.edu.javeriana.servired.reportes.dominio;

/** Coordenadas WGS84 dentro del rectángulo que contiene a Colombia (incluye San Andrés). */
public record Ubicacion(double latitud, double longitud) {

    public static final double LATITUD_MIN = -4.3;
    public static final double LATITUD_MAX = 13.5;
    public static final double LONGITUD_MIN = -82;
    public static final double LONGITUD_MAX = -66.8;

    private static final double RADIO_TIERRA_METROS = 6_371_000;

    /** Distancia sobre la superficie terrestre (fórmula del haversine). */
    public double distanciaMetros(Ubicacion otra) {
        double dLat = Math.toRadians(otra.latitud - latitud);
        double dLon = Math.toRadians(otra.longitud - longitud);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(latitud)) * Math.cos(Math.toRadians(otra.latitud)) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * RADIO_TIERRA_METROS * Math.asin(Math.sqrt(a));
    }

    public boolean estaEnColombia() {
        return latitud >= LATITUD_MIN && latitud <= LATITUD_MAX
                && longitud >= LONGITUD_MIN && longitud <= LONGITUD_MAX;
    }
}
