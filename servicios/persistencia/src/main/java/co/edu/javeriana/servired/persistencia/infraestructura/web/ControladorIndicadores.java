package co.edu.javeriana.servired.persistencia.infraestructura.web;

import co.edu.javeriana.servired.persistencia.aplicacion.OperacionesIndicadores;
import co.edu.javeriana.servired.persistencia.dominio.Indicadores.ActualizacionVista;
import co.edu.javeriana.servired.persistencia.dominio.Indicadores.Agregado;
import co.edu.javeriana.servired.persistencia.dominio.Indicadores.Agrupacion;
import co.edu.javeriana.servired.persistencia.dominio.Indicadores.EstadoVista;
import co.edu.javeriana.servired.persistencia.dominio.Indicadores.Filtro;
import co.edu.javeriana.servired.persistencia.dominio.Indicadores.PaginaHechos;
import co.edu.javeriana.servired.persistencia.dominio.ServicioCliente;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Vista de lectura de Indicadores (solo `indicadores`). */
@RestController
@RequestMapping("/interno/v1/indicadores")
public class ControladorIndicadores {

    private static final String SERVICIOS = "ENERGIA|GAS|ACUEDUCTO|ALCANTARILLADO|ASEO";

    private final OperacionesIndicadores indicadores;

    public ControladorIndicadores(OperacionesIndicadores indicadores) {
        this.indicadores = indicadores;
    }

    @PostMapping("/vista")
    public ResponseEntity<Void> actualizar(@RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s,
                                           @Valid @RequestBody SolicitudVista v) {
        indicadores.actualizarVista(s, new ActualizacionVista(v.idReporte(), v.fechaEvento(), v.tipoServicio(),
                v.codigoMunicipio(), v.latitud(), v.longitud(), v.magnitud(), v.inicioEstimado(), v.fechaRegistro(),
                v.fechaRestablecimiento(), v.duracionMinutos(), v.validado(), v.confirmacionesNetas(), v.estadoCaso(),
                v.prioridad(), v.idEmpresa(), v.resueltoEn(), v.cerradoEn()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/agregados")
    public List<Agregado> agregados(@RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s,
                                    @Valid @RequestBody SolicitudAgregados a) {
        return indicadores.agregados(s, a.filtro().aDominio(), a.agrupacion(), a.celdaGrados() == null ? 0.01 : a.celdaGrados());
    }

    @PostMapping("/hechos")
    public PaginaHechos hechos(@RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s,
                               @Valid @RequestBody SolicitudHechos h) {
        return indicadores.hechos(s, h.filtro().aDominio(), h.desde(), h.limite() == 0 ? 1000 : h.limite());
    }

    @GetMapping("/estado")
    public EstadoVista estado(@RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s) {
        return indicadores.estado(s);
    }

    public record SolicitudVista(@NotNull UUID idReporte, @NotNull Instant fechaEvento,
                                 @Pattern(regexp = SERVICIOS) String tipoServicio,
                                 @Pattern(regexp = "[0-9]{5}") String codigoMunicipio, Double latitud, Double longitud,
                                 @Pattern(regexp = "VIVIENDA|CUADRA|BARRIO|SECTOR") String magnitud,
                                 Instant inicioEstimado, Instant fechaRegistro, Instant fechaRestablecimiento,
                                 Integer duracionMinutos, Boolean validado, Integer confirmacionesNetas,
                                 @Pattern(regexp = "RECIBIDO|EN_ATENCION|RESUELTO|CERRADO") String estadoCaso,
                                 @Pattern(regexp = "BAJA|MEDIA|ALTA|CRITICA") String prioridad, UUID idEmpresa,
                                 Instant resueltoEn, Instant cerradoEn) {
    }

    public record FiltroDto(@NotNull Instant desde, @NotNull Instant hasta,
                            @Pattern(regexp = SERVICIOS) String tipoServicio,
                            @Pattern(regexp = "[0-9]{2}") String codigoDepartamento,
                            @Pattern(regexp = "[0-9]{5}") String codigoMunicipio, UUID idEmpresa,
                            @Pattern(regexp = "ACTIVO|RESUELTO") String situacion) {
        Filtro aDominio() {
            return new Filtro(desde, hasta, tipoServicio, codigoDepartamento, codigoMunicipio, idEmpresa, situacion);
        }
    }

    public record SolicitudAgregados(@NotNull @Valid FiltroDto filtro, @NotNull Agrupacion agrupacion, Double celdaGrados) {
    }

    public record SolicitudHechos(@NotNull @Valid FiltroDto filtro, int desde, int limite) {
    }
}
