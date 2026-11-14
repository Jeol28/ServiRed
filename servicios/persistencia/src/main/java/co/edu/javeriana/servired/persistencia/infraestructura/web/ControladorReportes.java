package co.edu.javeriana.servired.persistencia.infraestructura.web;

import co.edu.javeriana.servired.persistencia.aplicacion.ConsultarReporte;
import co.edu.javeriana.servired.persistencia.aplicacion.GuardarReporte;
import co.edu.javeriana.servired.persistencia.dominio.IdCorrelacion;
import co.edu.javeriana.servired.persistencia.dominio.ReporteGuardado;
import co.edu.javeriana.servired.persistencia.dominio.ReporteNuevo;
import co.edu.javeriana.servired.persistencia.dominio.ServicioCliente;
import co.edu.javeriana.servired.persistencia.infraestructura.web.Solicitudes.EventoDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operaciones de negocio del dominio de reportes (contratos/internos/persistencia.v1.yaml). */
@RestController
@RequestMapping("/interno/v1/reportes")
public class ControladorReportes {

    private final GuardarReporte guardarReporte;
    private final ConsultarReporte consultarReporte;
    private final Solicitudes solicitudes;

    public ControladorReportes(GuardarReporte guardarReporte, ConsultarReporte consultarReporte,
                               Solicitudes solicitudes) {
        this.guardarReporte = guardarReporte;
        this.consultarReporte = consultarReporte;
        this.solicitudes = solicitudes;
    }

    @PostMapping("/guardar")
    public ResponseEntity<ReporteGuardado> guardar(
            @RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente servicio,
            @RequestHeader(IdCorrelacion.CABECERA) String correlacion,
            @Valid @RequestBody SolicitudGuardarReporte solicitud) {
        var resultado = guardarReporte.ejecutar(servicio, new IdCorrelacion(correlacion),
                solicitud.reporte().aDominio(), solicitudes.evento(solicitud.evento()));
        return ResponseEntity.status(resultado.nuevo() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(resultado.reporte());
    }

    @GetMapping("/{idReporte}")
    public ResponseEntity<ReporteGuardado> consultar(
            @RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente servicio,
            @PathVariable UUID idReporte) {
        return ResponseEntity.of(consultarReporte.ejecutar(servicio, idReporte));
    }

    public record SolicitudGuardarReporte(@NotNull @Valid ReporteDto reporte, @NotNull @Valid EventoDto evento) {
    }

    public record ReporteDto(
            @NotNull UUID idReporte,
            @NotNull UUID idCiudadano,
            @NotNull @Pattern(regexp = "ENERGIA|GAS|ACUEDUCTO|ALCANTARILLADO|ASEO") String tipoServicio,
            @NotNull @DecimalMin("-4.3") @DecimalMax("13.5") Double latitud,
            @NotNull @DecimalMin("-82") @DecimalMax("-66.8") Double longitud,
            @NotNull @Pattern(regexp = "[0-9]{5}") String codigoMunicipio,
            @NotNull Instant inicioEstimado,
            @NotNull @Pattern(regexp = "VIVIENDA|CUADRA|BARRIO|SECTOR") String magnitud,
            @Size(max = 1000) String descripcion,
            @NotNull Instant fechaRegistro) {

        ReporteNuevo aDominio() {
            return new ReporteNuevo(idReporte, idCiudadano, tipoServicio, latitud, longitud, codigoMunicipio,
                    inicioEstimado, magnitud, descripcion, fechaRegistro);
        }
    }
}
