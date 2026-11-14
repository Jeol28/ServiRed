package co.edu.javeriana.servired.reportes.dominio;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reporte de corte no programado ya validado (RF-1, RNF-DAT1), antes de
 * resolver su municipio y persistirlo.
 */
public record NuevoReporte(UUID idReporte, UUID idCiudadano, TipoServicio tipoServicio, Ubicacion ubicacion,
                           Instant inicioEstimado, Magnitud magnitud, String descripcion, Instant fechaRegistro) {

    /** Margen por diferencias de reloj entre el dispositivo y el servidor. */
    public static final Duration TOLERANCIA_RELOJ = Duration.ofMinutes(5);
    /** Un corte reportado con más antigüedad no es un evento "en curso". */
    public static final Duration ANTIGUEDAD_MAXIMA = Duration.ofDays(30);
    public static final int LONGITUD_MAXIMA_DESCRIPCION = 1000;

    /**
     * Valida y construye el reporte. Reúne todos los errores en lugar de
     * detenerse en el primero, para que el cliente los muestre juntos.
     */
    public static NuevoReporte registrar(UUID idCiudadano, TipoServicio tipoServicio, Ubicacion ubicacion,
                                         Instant inicioEstimado, Magnitud magnitud, String descripcion,
                                         Instant ahora) {
        List<ErrorDeCampo> errores = new ArrayList<>();
        if (idCiudadano == null) {
            errores.add(new ErrorDeCampo("ciudadano", "es obligatorio"));
        }
        if (tipoServicio == null) {
            errores.add(new ErrorDeCampo("tipoServicio", "es obligatorio"));
        }
        if (ubicacion == null) {
            errores.add(new ErrorDeCampo("ubicacion", "es obligatoria"));
        } else if (!ubicacion.estaEnColombia()) {
            errores.add(new ErrorDeCampo("ubicacion", "debe estar dentro de Colombia"));
        }
        if (inicioEstimado == null) {
            errores.add(new ErrorDeCampo("inicioEstimado", "es obligatorio"));
        } else if (inicioEstimado.isAfter(ahora.plus(TOLERANCIA_RELOJ))) {
            errores.add(new ErrorDeCampo("inicioEstimado", "no puede ser futuro"));
        } else if (inicioEstimado.isBefore(ahora.minus(ANTIGUEDAD_MAXIMA))) {
            errores.add(new ErrorDeCampo("inicioEstimado", "no puede tener más de 30 días"));
        }
        if (magnitud == null) {
            errores.add(new ErrorDeCampo("magnitud", "es obligatoria"));
        }
        String texto = descripcion == null || descripcion.isBlank() ? null : descripcion.strip();
        if (texto != null && texto.length() > LONGITUD_MAXIMA_DESCRIPCION) {
            errores.add(new ErrorDeCampo("descripcion", "máximo 1000 caracteres"));
        }
        if (!errores.isEmpty()) {
            throw new ReporteInvalido(errores);
        }
        Instant inicio = inicioEstimado.isAfter(ahora) ? ahora : inicioEstimado;
        return new NuevoReporte(UUID.randomUUID(), idCiudadano, tipoServicio, ubicacion, inicio, magnitud,
                texto, ahora);
    }
}
