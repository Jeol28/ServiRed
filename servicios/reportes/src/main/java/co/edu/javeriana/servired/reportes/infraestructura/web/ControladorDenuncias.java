package co.edu.javeriana.servired.reportes.infraestructura.web;

import co.edu.javeriana.servired.reportes.aplicacion.RecibirDenuncia;
import co.edu.javeriana.servired.reportes.dominio.IdCorrelacion;
import co.edu.javeriana.servired.reportes.dominio.TipoServicio;
import co.edu.javeriana.servired.reportes.dominio.Ubicacion;
import co.edu.javeriana.servired.reportes.infraestructura.web.ControladorReportes.UbicacionDto;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Receptor de denuncias anónimas (RF-5, ADR-09). Público y sin token. No lee
 * ninguna cabecera ni dato del cliente que lo pueda identificar, y su log solo
 * contiene el id de la denuncia, el servicio y si trae evidencia.
 */
@RestController
@RequestMapping("/api/v1/denuncias")
public class ControladorDenuncias {

    private static final Logger log = LoggerFactory.getLogger(ControladorDenuncias.class);

    private final RecibirDenuncia recibir;

    public ControladorDenuncias(RecibirDenuncia recibir) {
        this.recibir = recibir;
    }

    /** Multipart: parte "denuncia" (JSON) y parte opcional "foto". */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DenunciaRecibidaDto> conFoto(@RequestPart("denuncia") NuevaDenunciaDto denuncia,
                                                       @RequestPart(value = "foto", required = false) MultipartFile foto)
            throws IOException {
        return recibir(denuncia, foto == null || foto.isEmpty() ? null : foto.getBytes());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DenunciaRecibidaDto> sinFoto(@RequestBody NuevaDenunciaDto denuncia) {
        return recibir(denuncia, null);
    }

    private ResponseEntity<DenunciaRecibidaDto> recibir(NuevaDenunciaDto d, byte[] foto) {
        Ubicacion u = d.ubicacion() == null || d.ubicacion().latitud() == null || d.ubicacion().longitud() == null
                ? null : new Ubicacion(d.ubicacion().latitud(), d.ubicacion().longitud());
        var r = recibir.ejecutar(d.tipoServicio(), u, d.descripcion(), foto,
                new IdCorrelacion(MDC.get(FiltroCorrelacion.CLAVE_MDC)));
        log.atInfo().addKeyValue("idDenuncia", r.idDenuncia()).addKeyValue("tipoServicio", d.tipoServicio())
                .addKeyValue("tieneEvidencia", r.tieneEvidencia()).log("denuncia anónima recibida");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new DenunciaRecibidaDto(r.idDenuncia(), r.estado(),
                r.fechaRecepcion(), r.tieneEvidencia(),
                "Gracias. La denuncia será validada; no guardamos datos que lo identifiquen."));
    }

    public record NuevaDenunciaDto(TipoServicio tipoServicio, UbicacionDto ubicacion, String descripcion) {
    }

    public record DenunciaRecibidaDto(UUID idDenuncia, String estado, Instant fechaRecepcion, boolean tieneEvidencia,
                                      String mensaje) {
    }
}
