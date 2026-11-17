package co.edu.javeriana.servired.reportes.dominio;

import java.util.List;
import java.util.Optional;

/**
 * Cómo se refleja un evento de caso en el estado que ve el ciudadano (ADR-07).
 * El estado del reporte solo avanza (RECIBIDO → EN_ATENCION → RESTABLECIDO →
 * CERRADO); {@code desde} son los estados a partir de los cuales aplica, así un
 * evento tardío o repetido no lo hace retroceder.
 */
public record EstadoPorCaso(EstadoReporte estadoNuevo, List<EstadoReporte> desde, String detalle) {

    public static Optional<EstadoPorCaso> para(String tipoEvento, String estadoCaso, String resolucion) {
        if ("caso.cerrado".equals(tipoEvento)) {
            return Optional.of(cerrado(resolucion));
        }
        if (!"caso.actualizado".equals(tipoEvento) || estadoCaso == null) {
            return Optional.empty();
        }
        return switch (estadoCaso) {
            case "EN_ATENCION" -> Optional.of(new EstadoPorCaso(EstadoReporte.EN_ATENCION,
                    List.of(EstadoReporte.RECIBIDO), "El operador está atendiendo el caso"));
            case "RESUELTO" -> Optional.of(new EstadoPorCaso(EstadoReporte.RESTABLECIDO,
                    List.of(EstadoReporte.RECIBIDO, EstadoReporte.EN_ATENCION), "El operador resolvió el caso"));
            case "CERRADO" -> Optional.of(cerrado(null));
            default -> Optional.empty();
        };
    }

    private static EstadoPorCaso cerrado(String resolucion) {
        String detalle = resolucion == null || resolucion.isBlank() ? "Caso cerrado"
                : "Caso cerrado: " + (resolucion.length() > 400 ? resolucion.substring(0, 400) : resolucion);
        return new EstadoPorCaso(EstadoReporte.CERRADO,
                List.of(EstadoReporte.RECIBIDO, EstadoReporte.EN_ATENCION, EstadoReporte.RESTABLECIDO), detalle);
    }
}
