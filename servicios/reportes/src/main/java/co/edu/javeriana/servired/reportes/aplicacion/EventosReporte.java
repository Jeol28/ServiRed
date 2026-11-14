package co.edu.javeriana.servired.reportes.aplicacion;

import co.edu.javeriana.servired.reportes.dominio.IdCorrelacion;
import co.edu.javeriana.servired.reportes.dominio.NuevoReporte;
import java.util.LinkedHashMap;
import java.util.Map;

/** Construye los eventos del dominio de reportes según sus esquemas versionados. */
public final class EventosReporte {

    private EventosReporte() {
    }

    /**
     * reporte.creado v1. No incluye el identificador del ciudadano: ningún
     * consumidor lo necesita (mínimo privilegio, RNF-SEG4).
     */
    public static SobreEvento reporteCreado(NuevoReporte r, String codigoMunicipio, IdCorrelacion correlacion) {
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("idReporte", r.idReporte().toString());
        datos.put("tipoServicio", r.tipoServicio().name());
        datos.put("ubicacion", Map.of("latitud", r.ubicacion().latitud(), "longitud", r.ubicacion().longitud()));
        datos.put("codigoMunicipio", codigoMunicipio);
        datos.put("inicioEstimado", r.inicioEstimado().toString());
        datos.put("magnitud", r.magnitud().name());
        if (r.descripcion() != null) {
            datos.put("descripcion", r.descripcion());
        }
        datos.put("fechaRegistro", r.fechaRegistro().toString());
        datos.put("tieneEvidencia", false);
        return SobreEvento.nuevo("reporte.creado", 1, correlacion, r.fechaRegistro(), datos);
    }
}
