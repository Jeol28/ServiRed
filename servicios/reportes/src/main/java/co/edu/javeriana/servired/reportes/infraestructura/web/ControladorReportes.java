package co.edu.javeriana.servired.reportes.infraestructura.web;

import co.edu.javeriana.servired.reportes.aplicacion.ConsultarReporte;
import co.edu.javeriana.servired.reportes.aplicacion.CrearReporte;
import co.edu.javeriana.servired.reportes.aplicacion.GestionEvidencias;
import co.edu.javeriana.servired.reportes.aplicacion.ParticipacionCiudadana;
import co.edu.javeriana.servired.reportes.aplicacion.Vistas;
import co.edu.javeriana.servired.reportes.dominio.EstadoReporte;
import co.edu.javeriana.servired.reportes.dominio.ObjetoValidacion;
import co.edu.javeriana.servired.reportes.dominio.SentidoValidacion;
import java.io.IOException;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
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
    private final ParticipacionCiudadana participacion;
    private final GestionEvidencias evidencias;
    private final CiudadanoActual ciudadano;

    public ControladorReportes(CrearReporte crearReporte, ConsultarReporte consultarReporte,
                               ParticipacionCiudadana participacion, GestionEvidencias evidencias,
                               CiudadanoActual ciudadano) {
        this.crearReporte = crearReporte;
        this.consultarReporte = consultarReporte;
        this.participacion = participacion;
        this.evidencias = evidencias;
        this.ciudadano = ciudadano;
    }

    /** RF-2: los reportes del ciudadano, del más reciente al más antiguo. */
    @GetMapping
    public PaginaDto mios(@RequestParam(defaultValue = "0") int pagina, @RequestParam(defaultValue = "20") int tamano) {
        var p = participacion.misReportes(ciudadano.id(), pagina, tamano, correlacion());
        return new PaginaDto(p.elementos().stream().map(ReporteDto::de).toList(), p.total(), p.pagina(), p.tamano());
    }

    /** RF-4: eventos activos cercanos que el ciudadano puede validar. */
    @GetMapping("/cercanos")
    public List<CercanoDto> cercanos(@RequestParam double latitud, @RequestParam double longitud) {
        return participacion.cercanos(ciudadano.id(), new Ubicacion(latitud, longitud), correlacion()).stream()
                .map(CercanoDto::de).toList();
    }

    /** RF-3: el autor registra el restablecimiento; se calcula la duración. */
    @PostMapping("/{idReporte}/restablecimiento")
    public ReporteDto restablecer(@PathVariable UUID idReporte, @RequestBody RestablecimientoDto cuerpo) {
        var reporte = participacion.restablecer(ciudadano.id(), idReporte,
                cuerpo.fechaRestablecimiento() == null ? null : cuerpo.fechaRestablecimiento().toInstant(),
                cuerpo.comentario(), correlacion());
        log.atInfo().addKeyValue("idReporte", idReporte).addKeyValue("duracionMinutos", reporte.duracionMinutos())
                .log("restablecimiento registrado");
        return ReporteDto.de(reporte);
    }

    /** RF-4: confirmar o controvertir un corte o restablecimiento cercano. */
    @PostMapping("/{idReporte}/validaciones")
    public ResponseEntity<ValidacionDto> validar(@PathVariable UUID idReporte, @RequestBody NuevaValidacionDto cuerpo) {
        Ubicacion u = cuerpo.ubicacion() == null || cuerpo.ubicacion().latitud() == null
                || cuerpo.ubicacion().longitud() == null ? null
                : new Ubicacion(cuerpo.ubicacion().latitud(), cuerpo.ubicacion().longitud());
        var r = participacion.validar(ciudadano.id(), idReporte, cuerpo.objeto(), cuerpo.sentido(), u, correlacion());
        log.atInfo().addKeyValue("idReporte", idReporte).addKeyValue("objeto", r.objeto())
                .addKeyValue("validado", r.validado()).log("validación comunitaria registrada");
        return ResponseEntity.status(r.nuevo() ? HttpStatus.CREATED : HttpStatus.OK).body(ValidacionDto.de(r));
    }

    /** RF-1: fotografía del reporte (multipart, parte "foto"). Se eliminan EXIF y GPS. */
    @PostMapping(value = "/{idReporte}/evidencias", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReporteDto> adjuntar(@PathVariable UUID idReporte, @RequestPart("foto") MultipartFile foto)
            throws IOException {
        var reporte = evidencias.adjuntar(ciudadano.id(), idReporte, foto.getBytes(), correlacion());
        return ResponseEntity.status(HttpStatus.CREATED).body(ReporteDto.de(reporte));
    }

    @GetMapping("/{idReporte}/evidencias/{idEvidencia}")
    public ResponseEntity<byte[]> evidencia(@PathVariable UUID idReporte, @PathVariable UUID idEvidencia) {
        var archivo = evidencias.obtener(ciudadano.id(), idReporte, idEvidencia, correlacion());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(archivo.tipoContenido()))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(archivo.contenido());
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

    public record RestablecimientoDto(OffsetDateTime fechaRestablecimiento, String comentario) {
    }

    public record NuevaValidacionDto(ObjetoValidacion objeto, SentidoValidacion sentido, UbicacionDto ubicacion) {
    }

    public record ValidacionDto(UUID idReporte, ObjetoValidacion objeto, int confirmaciones, int controversias,
                                int confirmacionesNetas, boolean validado) {
        static ValidacionDto de(Vistas.ResultadoValidacion r) {
            return new ValidacionDto(r.idReporte(), r.objeto(), r.conteo().confirmaciones(),
                    r.conteo().controversias(), r.conteo().netas(), r.validado());
        }
    }

    public record PaginaDto(List<ReporteDto> elementos, long total, int pagina, int tamano) {
    }

    /** Vista para validadores: sin autor ni descripción (minimización). */
    public record CercanoDto(UUID idReporte, TipoServicio tipoServicio, Magnitud magnitud, EstadoReporte estado,
                             java.time.Instant inicioEstimado, UbicacionDto ubicacion, int distanciaMetros,
                             ReporteDto.ConteoDto validacionCorte, ReporteDto.ConteoDto validacionRestablecimiento) {
        static CercanoDto de(Vistas.ReporteCercano c) {
            return new CercanoDto(c.idReporte(), c.tipoServicio(), c.magnitud(), c.estado(), c.inicioEstimado(),
                    new UbicacionDto(c.ubicacion().latitud(), c.ubicacion().longitud()), c.distanciaMetros(),
                    ReporteDto.ConteoDto.de(c.validacionCorte()), ReporteDto.ConteoDto.de(c.validacionRestablecimiento()));
        }
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
