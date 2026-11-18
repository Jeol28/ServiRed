package co.edu.javeriana.servired.reportes.dominio;

import java.time.Duration;
import java.time.Instant;

import java.util.UUID;

/**
 * Gestor de confirmaciones comunitarias (RF-4, ADR-08): un corte o un
 * restablecimiento queda validado con {@code umbral} confirmaciones netas de
 * ciudadanos distintos ubicados a máximo {@code radioMetros}. El autor no vota
 * su propio reporte y cada ciudadano vota una vez por objeto (lo garantiza
 * Persistencia). Validar no cambia el estado del reporte: evita cierres
 * prematuros; el cierre lo decide el operador.
 * <p>
 * Antigüedad mínima (ADR-22): el voto de una cuenta creada hace menos de
 * {@code antiguedadMinima} se registra (ese ciudadano ya votó) pero no suma al conteo ni al
 * umbral. Con el registro libre, crear cuentas en masa no sirve para validar al instante.
 */
public record ReglasValidacion(int umbral, int radioMetros, Duration antiguedadMinima) {

    public ReglasValidacion {
        if (umbral < 1 || radioMetros < 1) {
            throw new IllegalArgumentException("Umbral y radio deben ser positivos");
        }
        if (antiguedadMinima == null || antiguedadMinima.isNegative()) {
            throw new IllegalArgumentException("La antigüedad mínima no puede ser negativa");
        }
    }

    /** Sin antigüedad mínima: todo voto suma. */
    public ReglasValidacion(int umbral, int radioMetros) {
        this(umbral, radioMetros, Duration.ZERO);
    }

    /**
     * ¿Suma el voto de una cuenta creada en {@code cuentaCreadaEn}? Sin el dato (un token
     * anterior a la regla) suma: el token de acceso dura minutos y el siguiente ya lo trae.
     */
    public boolean cuentaParaUmbral(Instant cuentaCreadaEn, Instant ahora) {
        return cuentaCreadaEn == null || ahora == null || !cuentaCreadaEn.plus(antiguedadMinima).isAfter(ahora);
    }

    /** Voto de una cuenta establecida (suma). */
    public Resultado evaluar(Reporte reporte, UUID validador, ObjetoValidacion objeto, SentidoValidacion sentido,
                             Ubicacion ubicacionValidador) {
        return evaluar(reporte, validador, objeto, sentido, ubicacionValidador, null, null);
    }

    public Resultado evaluar(Reporte reporte, UUID validador, ObjetoValidacion objeto, SentidoValidacion sentido,
                             Ubicacion ubicacionValidador, Instant cuentaCreadaEn, Instant ahora) {
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
        boolean cuenta = cuentaParaUmbral(cuentaCreadaEn, ahora);
        Reporte.Conteo nuevo = cuenta ? reporte.conteo(objeto).con(sentido) : reporte.conteo(objeto);
        return new Resultado(objeto, sentido, nuevo, nuevo.netas() >= umbral, cuenta);
    }

    public boolean validado(Reporte.Conteo conteo) {
        return conteo.netas() >= umbral;
    }

    /** @param cuenta si el voto suma al conteo y al umbral (antigüedad de la cuenta) */
    public record Resultado(ObjetoValidacion objeto, SentidoValidacion sentido, Reporte.Conteo conteo,
                            boolean validado, boolean cuenta) {

        public Resultado(ObjetoValidacion objeto, SentidoValidacion sentido, Reporte.Conteo conteo, boolean validado) {
            this(objeto, sentido, conteo, validado, true);
        }
    }
}
