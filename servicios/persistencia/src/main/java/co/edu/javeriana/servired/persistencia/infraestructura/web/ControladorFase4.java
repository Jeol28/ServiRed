package co.edu.javeriana.servired.persistencia.infraestructura.web;

import co.edu.javeriana.servired.persistencia.aplicacion.GestionEvidencias;
import co.edu.javeriana.servired.persistencia.aplicacion.OperacionesReporte;
import co.edu.javeriana.servired.persistencia.dominio.Fase4.ArchivoNuevo;
import co.edu.javeriana.servired.persistencia.dominio.Fase4.DenunciaGuardada;
import co.edu.javeriana.servired.persistencia.dominio.Fase4.DenunciaNueva;
import co.edu.javeriana.servired.persistencia.dominio.Fase4.PaginaReportes;
import co.edu.javeriana.servired.persistencia.dominio.Fase4.ReporteCercano;
import co.edu.javeriana.servired.persistencia.dominio.Fase4.Restablecimiento;
import co.edu.javeriana.servired.persistencia.dominio.Fase4.VotoNuevo;
import co.edu.javeriana.servired.persistencia.dominio.IdCorrelacion;
import co.edu.javeriana.servired.persistencia.dominio.ReporteGuardado;
import co.edu.javeriana.servired.persistencia.dominio.ServicioCliente;
import co.edu.javeriana.servired.persistencia.infraestructura.web.Solicitudes.EventoDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Operaciones de la fase 4 del dominio de reportes (solo `reportes`). */
@RestController
@RequestMapping("/interno/v1")
public class ControladorFase4 {

    private final OperacionesReporte operaciones;
    private final GestionEvidencias evidencias;
    private final Solicitudes solicitudes;
    private final ObjectMapper json;
    private final Validator validador;

    public ControladorFase4(OperacionesReporte operaciones, GestionEvidencias evidencias, Solicitudes solicitudes,
                            ObjectMapper json, Validator validador) {
        this.operaciones = operaciones;
        this.evidencias = evidencias;
        this.solicitudes = solicitudes;
        this.json = json;
        this.validador = validador;
    }

    @GetMapping("/reportes")
    public PaginaReportes listar(@RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s,
                                 @RequestParam UUID idCiudadano, @RequestParam(defaultValue = "0") int pagina,
                                 @RequestParam(defaultValue = "20") int tamano) {
        return operaciones.listarDeCiudadano(s, idCiudadano, pagina, tamano);
    }

    @GetMapping("/reportes/cercanos")
    public List<ReporteCercano> cercanos(@RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s,
                                         @RequestParam double latitud, @RequestParam double longitud,
                                         @RequestParam(defaultValue = "1000") int radioMetros,
                                         @RequestParam(required = false) UUID excluirCiudadano,
                                         @RequestParam(defaultValue = "50") int limite) {
        return operaciones.cercanos(s, latitud, longitud, radioMetros, excluirCiudadano, limite);
    }

    @PostMapping("/reportes/{idReporte}/restablecimiento")
    public ReporteGuardado restablecimiento(@RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s,
                                            @RequestHeader(IdCorrelacion.CABECERA) String correlacion,
                                            @PathVariable UUID idReporte,
                                            @Valid @RequestBody SolicitudRestablecimiento r) {
        return operaciones.registrarRestablecimiento(s, new IdCorrelacion(correlacion), idReporte,
                new Restablecimiento(r.fechaRestablecimiento(), r.duracionMinutos(), r.comentario()),
                solicitudes.evento(r.evento()));
    }

    @PostMapping("/reportes/{idReporte}/validaciones")
    public ResponseEntity<ReporteGuardado> validacion(
            @RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s,
            @RequestHeader(IdCorrelacion.CABECERA) String correlacion, @PathVariable UUID idReporte,
            @Valid @RequestBody SolicitudValidacion v) {
        var reporte = operaciones.registrarValidacion(s, new IdCorrelacion(correlacion), idReporte,
                new VotoNuevo(v.objeto(), v.sentido(), v.idCiudadano(), v.fecha(), v.versionEsperada()),
                solicitudes.evento(v.evento()));
        return ResponseEntity.status(HttpStatus.CREATED).body(reporte);
    }

    /** Cuerpo: los bytes de la imagen ya saneada; Content-Type image/jpeg o image/png. */
    @PostMapping(value = "/reportes/{idReporte}/evidencias", consumes = {"image/jpeg", "image/png"})
    public ResponseEntity<ReporteGuardado> guardarEvidencia(
            @RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s,
            @RequestHeader(IdCorrelacion.CABECERA) String correlacion,
            @RequestHeader("X-Id-Evidencia") UUID idEvidencia,
            @RequestHeader("Content-Type") String tipo,
            @PathVariable UUID idReporte, @RequestBody byte[] contenido) {
        var reporte = evidencias.guardarDeReporte(s, new IdCorrelacion(correlacion), idReporte,
                new ArchivoNuevo(idEvidencia, tipoBase(tipo), contenido));
        return ResponseEntity.status(HttpStatus.CREATED).body(reporte);
    }

    @GetMapping("/reportes/{idReporte}/evidencias/{idEvidencia}")
    public ResponseEntity<byte[]> obtenerEvidencia(
            @RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s,
            @PathVariable UUID idReporte, @PathVariable UUID idEvidencia) {
        return evidencias.obtenerDeReporte(s, idReporte, idEvidencia)
                .map(a -> ResponseEntity.ok().contentType(MediaType.parseMediaType(a.tipoContenido())).body(a.contenido()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Multipart: parte "solicitud" (JSON con denuncia y evento) y parte opcional "foto". */
    @PostMapping(value = "/denuncias", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DenunciaGuardada> denuncia(
            @RequestAttribute(FiltroClaveServicio.ATRIBUTO_SERVICIO) ServicioCliente s,
            @RequestHeader(IdCorrelacion.CABECERA) String correlacion,
            @RequestPart("solicitud") String solicitudJson,
            @RequestPart(value = "foto", required = false) MultipartFile foto) throws IOException {
        SolicitudDenuncia sol = json.readValue(solicitudJson, SolicitudDenuncia.class);
        var violaciones = validador.validate(sol);
        if (!violaciones.isEmpty()) {
            throw new IllegalArgumentException("Solicitud de denuncia inválida");
        }
        var d = sol.denuncia();
        ArchivoNuevo archivo = foto == null || foto.isEmpty() ? null
                : new ArchivoNuevo(sol.idEvidencia() == null ? UUID.randomUUID() : sol.idEvidencia(),
                tipoBase(foto.getContentType()), foto.getBytes());
        var guardada = evidencias.guardarDenuncia(s, new IdCorrelacion(correlacion),
                new DenunciaNueva(d.idDenuncia(), d.tipoServicio(), d.latitud(), d.longitud(), d.codigoMunicipio(),
                        d.descripcion(), d.fechaRecepcion()), archivo, solicitudes.evento(sol.evento()));
        return ResponseEntity.status(HttpStatus.CREATED).body(guardada);
    }

    /** "image/png;charset=UTF-8" -> "image/png". */
    private static String tipoBase(String tipo) {
        if (tipo == null) {
            return null;
        }
        MediaType m = MediaType.parseMediaType(tipo);
        return m.getType() + "/" + m.getSubtype();
    }

    public record SolicitudRestablecimiento(@NotNull Instant fechaRestablecimiento, @Min(0) int duracionMinutos,
                                            @Size(max = 500) String comentario, @NotNull @Valid EventoDto evento) {
    }

    public record SolicitudValidacion(@NotNull @Pattern(regexp = "CORTE|RESTABLECIMIENTO") String objeto,
                                      @NotNull @Pattern(regexp = "CONFIRMA|CONTROVIERTE") String sentido,
                                      @NotNull UUID idCiudadano, @NotNull Instant fecha, @Min(0) int versionEsperada,
                                      @NotNull @Valid EventoDto evento) {
    }

    public record DenunciaDto(@NotNull UUID idDenuncia,
                              @NotNull @Pattern(regexp = "ENERGIA|GAS|ACUEDUCTO|ALCANTARILLADO|ASEO") String tipoServicio,
                              @NotNull @DecimalMin("-4.3") @DecimalMax("13.5") Double latitud,
                              @NotNull @DecimalMin("-82") @DecimalMax("-66.8") Double longitud,
                              @NotNull @Pattern(regexp = "[0-9]{5}") String codigoMunicipio,
                              @NotNull @Size(min = 10, max = 2000) String descripcion,
                              @NotNull Instant fechaRecepcion) {
    }

    public record SolicitudDenuncia(@NotNull @Valid DenunciaDto denuncia, UUID idEvidencia,
                                    @NotNull @Valid EventoDto evento) {
    }
}
