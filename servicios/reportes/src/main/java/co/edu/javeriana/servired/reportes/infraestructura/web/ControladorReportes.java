package co.edu.javeriana.servired.reportes.infraestructura.web;

import co.edu.javeriana.servired.reportes.aplicacion.ConsultarReporte;
import co.edu.javeriana.servired.reportes.aplicacion.CrearReporte;
import co.edu.javeriana.servired.reportes.dominio.IdCorrelacion;
import co.edu.javeriana.servired.reportes.dominio.Magnitud;
import co.edu.javeriana.servired.reportes.dominio.Reporte;
import co.edu.javeriana.servired.reportes.dominio.TipoServicio;
import co.edu.javeriana.servired.reportes.dominio.Ubicacion;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API pública de reportes (contratos/api-publica/servired-api.v1.yaml), detrás del gateway. */
@RestController
@RequestMapping("/api/v1/reportes")
public class ControladorReportes {

    private static final Logger log = LoggerFactory.getLogger(ControladorReportes.class);

    private final CrearReporte crearReporte;
    private final ConsultarReporte consultarReporte;
    private final CiudadanoActual ciudadano;

    public ControladorReportes(CrearReporte crearReporte, ConsultarReporte consultarReporte,
                               CiudadanoActual ciudadano) {
        this.crearReporte = crearReporte;
        this.consultarReporte = consultarReporte;
        this.ciudadano = ciudadano;
    }

    @PostMapping
    public ResponseEntity<ReporteDto> crear(@RequestBody NuevoReporteDto cuerpo) {
        Reporte reporte = crearReporte.ejecutar(ciudadano.id(), cuerpo.aComando(), correlacion());
        log.atInfo().addKeyValue("idReporte", reporte.idReporte())
                .addKeyValue("tipoServicio", reporte.tipoServicio())
                .addKeyValue("codigoMunicipio", reporte.municipio().codigo())
                .log("reporte registrado");
        return ResponseEntity.created(URI.create("/api/v1/reportes/" + reporte.idReporte()))
                .body(ReporteDto.de(reporte));
    }

    @GetMapping("/{idReporte}")
    public ReporteDto consultar(@PathVariable UUID idReporte) {
        return ReporteDto.de(consultarReporte.ejecutar(ciudadano.id(), idReporte, correlacion()));
    }

    private static IdCorrelacion correlacion() {
        return new IdCorrelacion(MDC.get(FiltroCorrelacion.CLAVE_MDC));
    }

    public record UbicacionDto(Double latitud, Double longitud) {
    }

    public record NuevoReporteDto(TipoServicio tipoServicio, UbicacionDto ubicacion, OffsetDateTime inicioEstimado,
                                  Magnitud magnitud, String descripcion) {

        CrearReporte.Comando aComando() {
            Ubicacion u = ubicacion == null || ubicacion.latitud() == null || ubicacion.longitud() == null
                    ? null : new Ubicacion(ubicacion.latitud(), ubicacion.longitud());
            return new CrearReporte.Comando(tipoServicio, u,
                    inicioEstimado == null ? null : inicioEstimado.toInstant(), magnitud, descripcion);
        }
    }
}
