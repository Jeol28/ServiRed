package co.edu.javeriana.servired.reportes.aplicacion;

import co.edu.javeriana.servired.reportes.dominio.IdCorrelacion;
import co.edu.javeriana.servired.reportes.dominio.NuevoReporte;
import co.edu.javeriana.servired.reportes.dominio.NuevaDenuncia;
import co.edu.javeriana.servired.reportes.dominio.ReglasValidacion;
import co.edu.javeriana.servired.reportes.dominio.Reporte;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;
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

    /** restablecimiento.registrado v1 (RF-3). */
    public static SobreEvento restablecimientoRegistrado(UUID idReporte, Reporte.Restablecimiento r,
                                                         IdCorrelacion correlacion, Instant ahora) {
        return SobreEvento.nuevo("restablecimiento.registrado", 1, correlacion, ahora, Map.of(
                "idReporte", idReporte.toString(),
                "fechaRestablecimiento", r.fecha().toString(),
                "duracionMinutos", r.duracionMinutos()));
    }

    /** validacion.registrada v1 (RF-4). No incluye la identidad del validador. */
    public static SobreEvento validacionRegistrada(UUID idReporte, ReglasValidacion.Resultado r,
                                                   IdCorrelacion correlacion, Instant ahora) {
        return SobreEvento.nuevo("validacion.registrada", 1, correlacion, ahora, Map.of(
                "idReporte", idReporte.toString(),
                "objeto", r.objeto().name(),
                "sentido", r.sentido().name(),
                "confirmacionesNetas", r.conteo().netas(),
                "validado", r.validado()));
    }

    /** denuncia.recibida v1 (RF-5). Sin ningún identificador del denunciante (RNF-SEG4). */
    public static SobreEvento denunciaRecibida(NuevaDenuncia d, String codigoMunicipio, boolean tieneEvidencia,
                                               IdCorrelacion correlacion) {
        return SobreEvento.nuevo("denuncia.recibida", 1, correlacion, d.fechaRecepcion(), Map.of(
                "idDenuncia", d.idDenuncia().toString(),
                "tipoServicio", d.tipoServicio().name(),
                "codigoMunicipio", codigoMunicipio,
                "ubicacion", Map.of("latitud", d.ubicacion().latitud(), "longitud", d.ubicacion().longitud()),
                "fechaRecepcion", d.fechaRecepcion().toString(),
                "tieneEvidencia", tieneEvidencia));
    }
}
