package co.edu.javeriana.servired.reportes.dominio;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Reporte persistido con su historia, restablecimiento, validaciones y evidencias (RF-1 a RF-4). */
public record Reporte(UUID idReporte, UUID idCiudadano, TipoServicio tipoServicio, Ubicacion ubicacion,
                      Municipio municipio, Instant inicioEstimado, Magnitud magnitud, String descripcion,
                      EstadoReporte estado, Instant fechaRegistro, Instant fechaRestablecimiento,
                      Integer duracionMinutos, int versionValidaciones, Conteo validacionCorte,
                      Conteo validacionRestablecimiento, List<EntradaHistoria> historia, List<Evidencia> evidencias) {

    public static final int LONGITUD_MAXIMA_COMENTARIO = 500;

    public Reporte {
        historia = List.copyOf(historia);
        evidencias = List.copyOf(evidencias);
        validacionCorte = validacionCorte == null ? Conteo.VACIO : validacionCorte;
        validacionRestablecimiento = validacionRestablecimiento == null ? Conteo.VACIO : validacionRestablecimiento;
    }

    public boolean perteneceA(UUID ciudadano) {
        return idCiudadano.equals(ciudadano);
    }

    public Conteo conteo(ObjetoValidacion objeto) {
        return objeto == ObjetoValidacion.CORTE ? validacionCorte : validacionRestablecimiento;
    }

    /**
     * RF-3: el autor registra que el servicio volvió. La duración es
     * restablecimiento − inicio estimado (ADR-08), en minutos completos.
     */
    public Restablecimiento restablecer(UUID solicitante, Instant fecha, String comentario, Instant ahora) {
        if (!perteneceA(solicitante)) {
            throw new ReporteNoEncontrado(idReporte);
        }
        // Si el operador ya resolvió el caso (RESTABLECIDO sin fecha), el ciudadano aún registra la hora real.
        boolean resueltoSinHora = estado == EstadoReporte.RESTABLECIDO && fechaRestablecimiento == null;
        if (estado != EstadoReporte.RECIBIDO && estado != EstadoReporte.EN_ATENCION && !resueltoSinHora) {
            throw new ReglaIncumplida("YA_RESTABLECIDO", "El reporte ya fue restablecido o cerrado");
        }
        if (fecha == null) {
            throw new ReporteInvalido(List.of(new ErrorDeCampo("fechaRestablecimiento", "es obligatoria")));
        }
        if (fecha.isAfter(ahora.plus(NuevoReporte.TOLERANCIA_RELOJ))) {
            throw new ReporteInvalido(List.of(new ErrorDeCampo("fechaRestablecimiento", "no puede ser futura")));
        }
        Instant ajustada = fecha.isAfter(ahora) ? ahora : fecha;
        if (!ajustada.isAfter(inicioEstimado)) {
            throw new ReporteInvalido(List.of(new ErrorDeCampo("fechaRestablecimiento",
                    "debe ser posterior al inicio del corte")));
        }
        String texto = comentario == null || comentario.isBlank() ? null : comentario.strip();
        if (texto != null && texto.length() > LONGITUD_MAXIMA_COMENTARIO) {
            throw new ReporteInvalido(List.of(new ErrorDeCampo("comentario", "máximo 500 caracteres")));
        }
        long minutos = Duration.between(inicioEstimado, ajustada).toMinutes();
        return new Restablecimiento(ajustada, (int) Math.min(minutos, Integer.MAX_VALUE), texto);
    }

    public record EntradaHistoria(EstadoReporte estado, Instant fecha, String detalle) {
    }

    public record Restablecimiento(Instant fecha, int duracionMinutos, String comentario) {
    }

    public record Evidencia(UUID idEvidencia, String tipoContenido, int tamanoBytes, Instant creadaEn) {
    }

    /** Votos de la validación comunitaria sobre un objeto. */
    public record Conteo(int confirmaciones, int controversias) {

        public static final Conteo VACIO = new Conteo(0, 0);

        public int netas() {
            return confirmaciones - controversias;
        }

        public Conteo con(SentidoValidacion sentido) {
            return sentido == SentidoValidacion.CONFIRMA
                    ? new Conteo(confirmaciones + 1, controversias)
                    : new Conteo(confirmaciones, controversias + 1);
        }
    }
}
