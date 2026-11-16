package co.edu.javeriana.servired.reportes.dominio;

import java.util.UUID;

/**
 * Gestor de confirmaciones comunitarias (RF-4, ADR-08): un corte o un
 * restablecimiento queda validado con {@code umbral} confirmaciones netas de
 * ciudadanos distintos ubicados a máximo {@code radioMetros}. El autor no vota
 * su propio reporte y cada ciudadano vota una vez por objeto (lo garantiza
 * Persistencia). Validar no cambia el estado del reporte: evita cierres
 * prematuros; el cierre lo decide el operador.
 */
public record ReglasValidacion(int umbral, int radioMetros) {

    public ReglasValidacion {
        if (umbral < 1 || radioMetros < 1) {
            throw new IllegalArgumentException("Umbral y radio deben ser positivos");
        }
    }

    public Resultado evaluar(Reporte reporte, UUID validador, ObjetoValidacion objeto, SentidoValidacion sentido,
                             Ubicacion ubicacionValidador) {
        if (objeto == null || sentido == null || ubicacionValidador == null) {
            throw new ReporteInvalido(java.util.List.of(new ErrorDeCampo("validacion",
                    "objeto, sentido y ubicacion son obligatorios")));
        }
        if (reporte.perteneceA(validador)) {
            throw new ReglaIncumplida("AUTOR", "El autor no puede validar su propio reporte");
        }
        if (reporte.estado() == EstadoReporte.CERRADO) {
            throw new ReglaIncumplida("CERRADO", "El reporte ya está cerrado");
        }
        if (objeto == ObjetoValidacion.RESTABLECIMIENTO && reporte.estado() != EstadoReporte.RESTABLECIDO) {
            throw new ReglaIncumplida("SIN_RESTABLECIMIENTO", "El reporte aún no tiene restablecimiento");
        }
        double distancia = reporte.ubicacion().distanciaMetros(ubicacionValidador);
        if (distancia > radioMetros) {
            throw new ReglaIncumplida("LEJOS", "Debe estar a máximo " + radioMetros + " m del evento");
        }
        Reporte.Conteo nuevo = reporte.conteo(objeto).con(sentido);
        return new Resultado(objeto, sentido, nuevo, nuevo.netas() >= umbral);
    }

    public boolean validado(Reporte.Conteo conteo) {
        return conteo.netas() >= umbral;
    }

    public record Resultado(ObjetoValidacion objeto, SentidoValidacion sentido, Reporte.Conteo conteo,
                            boolean validado) {
    }
}
