package co.edu.javeriana.servired.persistencia.infraestructura.web;

import co.edu.javeriana.servired.persistencia.aplicacion.AbrirCaso;
import co.edu.javeriana.servired.persistencia.aplicacion.GestionEvidencias;
import co.edu.javeriana.servired.persistencia.aplicacion.OperacionesAtencion;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.CambiosCaso;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.Decision;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.Cobertura;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.ConsultaCobertura;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.EmpresaPrestadora;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.FiltroCasos;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.Moderacion;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.ModeracionNueva;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.PaginaCasos;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.PaginaModeraciones;
import co.edu.javeriana.servired.persistencia.dominio.CasoGuardado;
import co.edu.javeriana.servired.persistencia.dominio.CasoNuevo;
import co.edu.javeriana.servired.persistencia.dominio.IdCorrelacion;
import co.edu.javeriana.servired.persistencia.dominio.ServicioCliente;
import co.edu.javeriana.servired.persistencia.infraestructura.web.Solicitudes.EventoDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operaciones de negocio del dominio de atención (solo `atencion`). */
@RestController
@RequestMapping("/interno/v1")
public class ControladorCasos {

    private static final String SERVICIOS = "ENERGIA|GAS|ACUEDUCTO|ALCANTARILLADO|ASEO";
    private static final String PRIORIDADES = "BAJA|MEDIA|ALTA|CRITICA";

    private final AbrirCaso abrirCaso;
    private final OperacionesAtencion atencion;
    private final GestionEvidencias evidencias;
    private final Solicitudes solicitudes;

    public ControladorCasos(AbrirCaso abrirCaso, OperacionesAtencion atencion, GestionEvidencias evidencias,
                            Solicitudes solicitudes) {
        this.abrirCaso = abrirCaso;
        this.atencion = atencion;
        this.evidencias = evidencias;
        this.solicitudes = solicitudes;
    }

    @PostMapping("/casos/abrir")
    public ResponseEntity<CasoGuardado> abrir(
            @RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente servicio,
            @RequestHeader(IdCorrelacion.CABECERA) String correlacion,
            @Valid @RequestBody SolicitudAbrirCaso s) {
        var resultado = abrirCaso.ejecutar(servicio, new IdCorrelacion(correlacion),
                new CasoNuevo(s.idCaso(), s.idReporte(), s.tipoServicio(), s.codigoMunicipio(), s.magnitud(),
                        s.abiertoEn(), s.idEmpresa(), s.prioridad(), s.latitud(), s.longitud(), s.descripcion(),
                        s.inicioEstimado()),
                s.eventos() == null ? List.of() : s.eventos().stream().map(solicitudes::evento).toList());
        return ResponseEntity.status(resultado.nuevo() ? HttpStatus.CREATED : HttpStatus.OK).body(resultado.caso());
    }

    @GetMapping("/casos/{idCaso}")
    public ResponseEntity<CasoGuardado> caso(@RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s,
                                             @PathVariable UUID idCaso) {
        return ResponseEntity.of(atencion.caso(s, idCaso));
    }

    @GetMapping("/casos")
    public PaginaCasos listar(@RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s,
                              @RequestParam(required = false) String estado,
                              @RequestParam(required = false) String tipoServicio,
                              @RequestParam(required = false) String codigoMunicipio,
                              @RequestParam(required = false) String codigoDepartamento,
                              @RequestParam(required = false) String prioridad,
                              @RequestParam(required = false) UUID idEmpresa,
                              @RequestParam(defaultValue = "false") boolean sinEmpresa,
                              @RequestParam(required = false) UUID asignadoA,
                              @RequestParam(required = false) UUID idReporte,
                              @RequestParam(defaultValue = "0") int pagina,
                              @RequestParam(defaultValue = "20") int tamano) {
        return atencion.listar(s, new FiltroCasos(estado, tipoServicio, codigoMunicipio, codigoDepartamento, prioridad, idEmpresa,
                sinEmpresa, asignadoA, idReporte, pagina, tamano));
    }

    @PostMapping("/casos/{idCaso}/actualizar")
    public CasoGuardado actualizar(@RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s,
                                   @RequestHeader(IdCorrelacion.CABECERA) String correlacion,
                                   @PathVariable UUID idCaso, @Valid @RequestBody SolicitudActualizar c) {
        var eventos = c.eventos() == null ? List.<EventoDto>of() : c.eventos();
        return atencion.actualizar(s, new IdCorrelacion(correlacion), idCaso,
                new CambiosCaso(c.versionEsperada(), c.estado(), c.prioridad(), c.prioridadManual(), c.asignadoA(),
                        Boolean.TRUE.equals(c.quitarAsignacion()), c.idEmpresa(), c.corteValidado(), c.resolucion(),
                        c.resueltoEn(), c.cerradoEn(), c.detalleHistoria(), c.autor(), c.fecha()),
                eventos.stream().map(solicitudes::evento).toList());
    }

    @GetMapping("/casos/{idCaso}/evidencias/{idEvidencia}")
    public ResponseEntity<byte[]> evidenciaDeCaso(
            @RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s,
            @PathVariable UUID idCaso, @PathVariable UUID idEvidencia) {
        return atencion.evidenciaDeCaso(s, idCaso, idEvidencia)
                .map(a -> ResponseEntity.ok().contentType(MediaType.parseMediaType(a.tipoContenido())).body(a.contenido()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Zona y empresa dueña (ADR-35): por punto, por la ubicación de una denuncia o por municipio. */
    @GetMapping("/catalogo/cobertura")
    public ResponseEntity<Cobertura> cobertura(
            @RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s,
            @RequestParam String tipoServicio, @RequestParam(required = false) Double latitud,
            @RequestParam(required = false) Double longitud, @RequestParam(required = false) UUID idDenuncia,
            @RequestParam(required = false) String codigoMunicipio) {
        return ResponseEntity.of(atencion.cobertura(s,
                new ConsultaCobertura(latitud, longitud, idDenuncia, codigoMunicipio, tipoServicio)));
    }

    @GetMapping("/catalogo/empresas")
    public List<EmpresaPrestadora> empresas(@RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s) {
        return atencion.empresas(s);
    }

    @GetMapping("/operadores/{idCuenta}")
    public Map<String, Boolean> operador(@RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s,
                                         @PathVariable UUID idCuenta, @RequestParam UUID idEmpresa) {
        return Map.of("operadorDeLaEmpresa", atencion.esOperadorDe(s, idCuenta, idEmpresa));
    }

    // ------------------------------------------------------------- moderación
    @PostMapping("/moderaciones/abrir")
    public Moderacion abrirModeracion(@RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s,
                                      @Valid @RequestBody SolicitudModeracion m) {
        return atencion.abrirModeracion(s, new ModeracionNueva(m.idDenuncia(), m.idEmpresa(), m.recibidaEn()));
    }

    @GetMapping("/moderaciones")
    public PaginaModeraciones moderaciones(@RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s,
                                           @RequestParam(required = false) String estado,
                                           @RequestParam(required = false) UUID idEmpresa,
                                           @RequestParam(defaultValue = "0") int pagina,
                                           @RequestParam(defaultValue = "20") int tamano) {
        return atencion.listarModeraciones(s, estado, idEmpresa, pagina, tamano);
    }

    @GetMapping("/moderaciones/{idDenuncia}")
    public ResponseEntity<Moderacion> moderacion(@RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s,
                                                 @PathVariable UUID idDenuncia) {
        return ResponseEntity.of(atencion.moderacion(s, idDenuncia));
    }

    @PostMapping("/moderaciones/{idDenuncia}/decidir")
    public Moderacion decidir(@RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s,
                              @RequestHeader(IdCorrelacion.CABECERA) String correlacion,
                              @PathVariable UUID idDenuncia, @Valid @RequestBody SolicitudDecision d) {
        return atencion.decidir(s, new IdCorrelacion(correlacion), idDenuncia,
                new Decision(d.versionEsperada(), d.estado(), d.nota(), d.moderadaPor(), d.fecha()),
                d.eventos() == null ? List.of() : d.eventos().stream().map(solicitudes::evento).toList());
    }

    @GetMapping("/denuncias/{idDenuncia}/evidencias/{idEvidencia}")
    public ResponseEntity<byte[]> evidenciaDeDenuncia(
            @RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s,
            @PathVariable UUID idDenuncia, @PathVariable UUID idEvidencia) {
        return atencion.evidenciaDeDenuncia(s, idDenuncia, idEvidencia)
                .map(a -> ResponseEntity.ok().contentType(MediaType.parseMediaType(a.tipoContenido())).body(a.contenido()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record SolicitudAbrirCaso(
            @NotNull UUID idCaso, @NotNull UUID idReporte,
            @NotNull @Pattern(regexp = SERVICIOS) String tipoServicio,
            @NotNull @Pattern(regexp = "[0-9]{5}") String codigoMunicipio,
            @NotNull @Pattern(regexp = "VIVIENDA|CUADRA|BARRIO|SECTOR") String magnitud,
            @NotNull Instant abiertoEn, UUID idEmpresa, @Pattern(regexp = PRIORIDADES) String prioridad,
            Double latitud, Double longitud, @Size(max = 1000) String descripcion, Instant inicioEstimado,
            List<@Valid EventoDto> eventos) {
    }

    public record SolicitudActualizar(
            @Min(0) int versionEsperada,
            @Pattern(regexp = "RECIBIDO|EN_ATENCION|RESUELTO|CERRADO") String estado,
            @Pattern(regexp = PRIORIDADES) String prioridad, Boolean prioridadManual, UUID asignadoA,
            Boolean quitarAsignacion, UUID idEmpresa, Boolean corteValidado, @Size(max = 1000) String resolucion,
            Instant resueltoEn, Instant cerradoEn, @Size(max = 500) String detalleHistoria, UUID autor,
            @NotNull Instant fecha, List<@Valid EventoDto> eventos) {
    }

    public record SolicitudModeracion(@NotNull UUID idDenuncia, UUID idEmpresa, @NotNull Instant recibidaEn) {
    }

    public record SolicitudDecision(@Min(0) int versionEsperada, @NotNull @Pattern(regexp = "VALIDADA|DESCARTADA") String estado,
                                    @NotNull @Size(min = 5, max = 1000) String nota, @NotNull UUID moderadaPor,
                                    @NotNull Instant fecha, List<@Valid EventoDto> eventos) {
    }
}
