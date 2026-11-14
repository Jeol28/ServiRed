package co.edu.javeriana.servired.persistencia.infraestructura.web;

import co.edu.javeriana.servired.persistencia.aplicacion.AbrirCaso;
import co.edu.javeriana.servired.persistencia.dominio.CasoGuardado;
import co.edu.javeriana.servired.persistencia.dominio.CasoNuevo;
import co.edu.javeriana.servired.persistencia.dominio.IdCorrelacion;
import co.edu.javeriana.servired.persistencia.dominio.ServicioCliente;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operaciones de negocio del dominio de atención. */
@RestController
@RequestMapping("/interno/v1/casos")
public class ControladorCasos {

    private final AbrirCaso abrirCaso;

    public ControladorCasos(AbrirCaso abrirCaso) {
        this.abrirCaso = abrirCaso;
    }

    @PostMapping("/abrir")
    public ResponseEntity<CasoGuardado> abrir(
            @RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente servicio,
            @RequestHeader(IdCorrelacion.CABECERA) String correlacion,
            @Valid @RequestBody SolicitudAbrirCaso s) {
        var resultado = abrirCaso.ejecutar(servicio, new IdCorrelacion(correlacion),
                new CasoNuevo(s.idCaso(), s.idReporte(), s.tipoServicio(), s.codigoMunicipio(),
                        s.magnitud(), s.abiertoEn()));
        return ResponseEntity.status(resultado.nuevo() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(resultado.caso());
    }

    public record SolicitudAbrirCaso(
            @NotNull UUID idCaso,
            @NotNull UUID idReporte,
            @NotNull @Pattern(regexp = "ENERGIA|GAS|ACUEDUCTO|ALCANTARILLADO|ASEO") String tipoServicio,
            @NotNull @Pattern(regexp = "[0-9]{5}") String codigoMunicipio,
            @NotNull @Pattern(regexp = "VIVIENDA|CUADRA|BARRIO|SECTOR") String magnitud,
            @NotNull Instant abiertoEn) {
    }
}
