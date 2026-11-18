package co.edu.javeriana.servired.reportes.infraestructura.persistencia;

import co.edu.javeriana.servired.reportes.aplicacion.SobreEvento;
import co.edu.javeriana.servired.reportes.aplicacion.Vistas.Archivo;
import co.edu.javeriana.servired.reportes.aplicacion.Vistas.DenunciaRecibida;
import co.edu.javeriana.servired.reportes.aplicacion.Vistas.ImagenSaneada;
import co.edu.javeriana.servired.reportes.aplicacion.Vistas.PaginaReportes;
import co.edu.javeriana.servired.reportes.aplicacion.Vistas.ReporteCercano;
import co.edu.javeriana.servired.reportes.aplicacion.Vistas.Voto;
import co.edu.javeriana.servired.reportes.aplicacion.puertos.CatalogoTerritorial;
import co.edu.javeriana.servired.reportes.aplicacion.puertos.RepositorioReportes;
import co.edu.javeriana.servired.reportes.dominio.EstadoReporte;
import co.edu.javeriana.servired.reportes.dominio.IdCorrelacion;
import co.edu.javeriana.servired.reportes.dominio.Magnitud;
import co.edu.javeriana.servired.reportes.dominio.Municipio;
import co.edu.javeriana.servired.reportes.dominio.NuevaDenuncia;
import co.edu.javeriana.servired.reportes.dominio.NuevoReporte;
import co.edu.javeriana.servired.reportes.dominio.ReglaIncumplida;
import co.edu.javeriana.servired.reportes.dominio.Reporte;
import co.edu.javeriana.servired.reportes.dominio.TipoServicio;
import co.edu.javeriana.servired.reportes.dominio.Ubicacion;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * Adaptador hacia la API interna de Persistencia (contratos/internos/persistencia.v1.yaml).
 * Reintenta ante fallas de red o 5xx: es seguro porque cada operación es
 * idempotente por X-Correlation-Id. Cada intento pasa por el circuit breaker
 * (ADR-20): con el circuito abierto no se reintenta ni se espera un timeout, se
 * falla al instante con {@link PersistenciaNoDisponible} (503).
 */
public class ClientePersistencia implements RepositorioReportes, CatalogoTerritorial {

    private static final Logger log = LoggerFactory.getLogger(ClientePersistencia.class);
    private static final String CABECERA_CORRELACION = IdCorrelacion.CABECERA;

    private final RestClient http;
    private final PropiedadesPersistencia propiedades;
    private final ObjectMapper json;
    private final CircuitBreaker circuito;

    public ClientePersistencia(RestClient.Builder constructor, PropiedadesPersistencia propiedades, ObjectMapper json) {
        this(constructor, propiedades, json, CircuitoPersistencia.crear(PropiedadesCircuito.porDefecto()));
    }

    public ClientePersistencia(RestClient.Builder constructor, PropiedadesPersistencia propiedades, ObjectMapper json,
                               CircuitBreaker circuito) {
        this.propiedades = propiedades;
        this.json = json;
        this.circuito = circuito;
        this.http = constructor.clone()
                .baseUrl(propiedades.url())
                .defaultHeader("X-Clave-Servicio", propiedades.clave())
                .build();
    }

    // --------------------------------------------------------------- catálogo
    @Override
    public Municipio resolverMunicipio(Ubicacion u, IdCorrelacion correlacion) {
        var m = conReintentos("resolverMunicipio", () -> http.get()
                .uri("/interno/v1/catalogo/municipio-cercano?latitud={lat}&longitud={lon}", u.latitud(), u.longitud())
                .header(CABECERA_CORRELACION, correlacion.valor())
                .retrieve()
                .body(MunicipioDto.class));
        return new Municipio(m.codigo(), m.nombre());
    }

    // --------------------------------------------------------------- reportes
    @Override
    public Reporte guardar(NuevoReporte r, String codigoMunicipio, SobreEvento evento, IdCorrelacion correlacion) {
        var solicitud = new SolicitudGuardar(new ReporteDto(r.idReporte(), r.idCiudadano(), r.tipoServicio().name(),
                r.ubicacion().latitud(), r.ubicacion().longitud(), codigoMunicipio, r.inicioEstimado(),
                r.magnitud().name(), r.descripcion(), r.fechaRegistro()), evento);
        return conReintentos("guardarReporte", () -> http.post()
                .uri("/interno/v1/reportes/guardar")
                .header(CABECERA_CORRELACION, correlacion.valor())
                .body(solicitud)
                .retrieve()
                .body(ReporteGuardadoDto.class))
                .aDominio();
    }

    @Override
    public Optional<Reporte> buscar(UUID idReporte, IdCorrelacion correlacion) {
        return opcional(() -> conReintentos("consultarReporte", () -> http.get()
                .uri("/interno/v1/reportes/{id}", idReporte)
                .header(CABECERA_CORRELACION, correlacion.valor())
                .retrieve()
                .body(ReporteGuardadoDto.class))).map(ReporteGuardadoDto::aDominio);
    }

    @Override
    public PaginaReportes listarDeCiudadano(UUID idCiudadano, int pagina, int tamano, IdCorrelacion correlacion) {
        var p = conReintentos("listarReportesDeCiudadano", () -> http.get()
                .uri("/interno/v1/reportes?idCiudadano={c}&pagina={p}&tamano={t}", idCiudadano, pagina, tamano)
                .header(CABECERA_CORRELACION, correlacion.valor())
                .retrieve()
                .body(PaginaDto.class));
        return new PaginaReportes(p.elementos().stream().map(ReporteGuardadoDto::aDominio).toList(), p.total(),
                p.pagina(), p.tamano());
    }

    @Override
    public List<ReporteCercano> cercanos(Ubicacion c, int radioMetros, UUID excluir, IdCorrelacion correlacion) {
        CercanoDto[] lista = conReintentos("buscarReportesCercanos", () -> http.get()
                .uri("/interno/v1/reportes/cercanos?latitud={lat}&longitud={lon}&radioMetros={r}&excluirCiudadano={e}",
                        c.latitud(), c.longitud(), radioMetros, excluir)
                .header(CABECERA_CORRELACION, correlacion.valor())
                .retrieve()
                .body(CercanoDto[].class));
        return List.of(lista).stream().map(CercanoDto::aVista).toList();
    }

    @Override
    public Reporte registrarRestablecimiento(UUID idReporte, Reporte.Restablecimiento r, SobreEvento evento,
                                             IdCorrelacion correlacion) {
        try {
            return conReintentos("registrarRestablecimiento", () -> http.post()
                    .uri("/interno/v1/reportes/{id}/restablecimiento", idReporte)
                    .header(CABECERA_CORRELACION, correlacion.valor())
                    .body(Map.of("fechaRestablecimiento", r.fecha(), "duracionMinutos", r.duracionMinutos(),
                            "comentario", r.comentario() == null ? "" : r.comentario(), "evento", evento))
                    .retrieve()
                    .body(ReporteGuardadoDto.class)).aDominio();
        } catch (HttpClientErrorException.Conflict e) {
            throw new ReglaIncumplida("YA_RESTABLECIDO", "El reporte ya fue restablecido o cerrado");
        }
    }

    @Override
    public Reporte registrarValidacion(UUID idReporte, Voto v, SobreEvento evento, IdCorrelacion correlacion) {
        try {
            return conReintentos("registrarValidacion", () -> http.post()
                    .uri("/interno/v1/reportes/{id}/validaciones", idReporte)
                    .header(CABECERA_CORRELACION, correlacion.valor())
                    .body(Map.of("objeto", v.objeto().name(), "sentido", v.sentido().name(),
                            "idCiudadano", v.idCiudadano(), "fecha", v.fecha(),
                            "versionEsperada", v.versionEsperada(), "evento", evento))
                    .retrieve()
                    .body(ReporteGuardadoDto.class)).aDominio();
        } catch (HttpClientErrorException.Conflict e) {
            if (e.getResponseBodyAsString().contains("YA_VOTO")) {
                throw new VotoRepetido();
            }
            throw new ConflictoDeVersion();
        }
    }

    @Override
    public Reporte aplicarEstadoDeCaso(UUID idReporte, UUID idEvento,
                                       co.edu.javeriana.servired.reportes.dominio.EstadoPorCaso c, Instant fecha,
                                       IdCorrelacion correlacion) {
        return conReintentos("aplicarEstadoDeCaso", () -> http.post()
                .uri("/interno/v1/reportes/{id}/estado-por-caso", idReporte)
                .header(CABECERA_CORRELACION, correlacion.valor())
                .body(Map.of("idEvento", idEvento, "estadoNuevo", c.estadoNuevo().name(),
                        "desde", c.desde().stream().map(Enum::name).toList(), "detalle", c.detalle(), "fecha", fecha))
                .retrieve()
                .body(ReporteGuardadoDto.class)).aDominio();
    }

    // --------------------------------------------------------------- evidencias
    @Override
    public Reporte guardarEvidencia(UUID idReporte, UUID idEvidencia, ImagenSaneada imagen, IdCorrelacion correlacion) {
        try {
            return conReintentos("guardarEvidencia", () -> http.post()
                    .uri("/interno/v1/reportes/{id}/evidencias", idReporte)
                    .header(CABECERA_CORRELACION, correlacion.valor())
                    .header("X-Id-Evidencia", idEvidencia.toString())
                    .contentType(MediaType.parseMediaType(imagen.tipoContenido()))
                    .body(imagen.contenido())
                    .retrieve()
                    .body(ReporteGuardadoDto.class)).aDominio();
        } catch (HttpClientErrorException.Conflict e) {
            throw new ReglaIncumplida("LIMITE_EVIDENCIAS", "El reporte ya tiene el máximo de fotografías");
        }
    }

    @Override
    public Optional<Archivo> obtenerEvidencia(UUID idReporte, UUID idEvidencia, IdCorrelacion correlacion) {
        return opcional(() -> conReintentos("obtenerEvidencia", () -> http.get()
                .uri("/interno/v1/reportes/{r}/evidencias/{e}", idReporte, idEvidencia)
                .header(CABECERA_CORRELACION, correlacion.valor())
                .retrieve()
                .toEntity(byte[].class)))
                .map(r -> new Archivo(String.valueOf(r.getHeaders().getContentType()), r.getBody()));
    }

    // --------------------------------------------------------------- denuncias
    @Override
    public DenunciaRecibida guardarDenuncia(NuevaDenuncia d, String codigoMunicipio, ImagenSaneada foto,
                                            SobreEvento evento, IdCorrelacion correlacion) {
        var partes = new LinkedMultiValueMap<String, Object>();
        partes.add("solicitud", parte(aJson(Map.of(
                "denuncia", Map.of("idDenuncia", d.idDenuncia(), "tipoServicio", d.tipoServicio().name(),
                        "latitud", d.ubicacion().latitud(), "longitud", d.ubicacion().longitud(),
                        "codigoMunicipio", codigoMunicipio, "descripcion", d.descripcion(),
                        "fechaRecepcion", d.fechaRecepcion()),
                "evento", evento)), MediaType.APPLICATION_JSON, null));
        if (foto != null) {
            String nombre = "image/png".equals(foto.tipoContenido()) ? "foto.png" : "foto.jpg";
            partes.add("foto", parte(new ByteArrayResource(foto.contenido()) {
                @Override
                public String getFilename() {
                    return nombre;
                }
            }, MediaType.parseMediaType(foto.tipoContenido()), nombre));
        }
        var cuerpo = partes;
        var r = conReintentos("guardarDenuncia", () -> http.post()
                .uri("/interno/v1/denuncias")
                .header(CABECERA_CORRELACION, correlacion.valor())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(cuerpo)
                .retrieve()
                .body(DenunciaDto.class));
        return new DenunciaRecibida(r.idDenuncia(), r.estado(), r.fechaRecepcion(), r.tieneEvidencia());
    }

    // --------------------------------------------------------------- auxiliares
    private static HttpEntity<Object> parte(Object contenido, MediaType tipo, String archivo) {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setContentType(tipo);
        return new HttpEntity<>(contenido, cabeceras);
    }

    private String aJson(Object valor) {
        try {
            return json.writeValueAsString(valor);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> Optional<T> opcional(Supplier<T> llamada) {
        try {
            return Optional.ofNullable(llamada.get());
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    private <T> T conReintentos(String operacion, Supplier<T> llamada) {
        RuntimeException ultima = null;
        for (int intento = 1; intento <= propiedades.intentos(); intento++) {
            try {
                return circuito.executeSupplier(llamada);
            } catch (CallNotPermittedException e) {
                throw new PersistenciaNoDisponible(
                        "Persistencia no disponible para " + operacion + " (circuito abierto)", e);
            } catch (ResourceAccessException | HttpServerErrorException e) {
                ultima = e;
                log.warn("persistencia falló en {} (intento {}/{}): {}", operacion, intento,
                        propiedades.intentos(), e.getMessage());
                esperar(intento);
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode() == HttpStatus.NOT_FOUND || e.getStatusCode() == HttpStatus.CONFLICT) {
                    throw e;
                }
                // Otro 4xx: error de integración (clave, contrato), no del ciudadano.
                throw new IllegalStateException("Persistencia rechazó " + operacion + ": " + e.getStatusCode(), e);
            }
        }
        throw new PersistenciaNoDisponible("Persistencia no disponible para " + operacion, ultima);
    }

    private void esperar(int intento) {
        if (intento == propiedades.intentos()) {
            return;
        }
        try {
            Thread.sleep(propiedades.esperaEntreIntentos().toMillis() * intento);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    record MunicipioDto(String codigo, String nombre, String departamento) {
    }

    record SolicitudGuardar(ReporteDto reporte, SobreEvento evento) {
    }

    record ReporteDto(UUID idReporte, UUID idCiudadano, String tipoServicio, double latitud, double longitud,
                      String codigoMunicipio, Instant inicioEstimado, String magnitud, String descripcion,
                      Instant fechaRegistro) {
    }

    record ConteoDto(int confirmaciones, int controversias) {
        Reporte.Conteo aDominio() {
            return new Reporte.Conteo(confirmaciones, controversias);
        }
    }

    record EvidenciaDto(UUID idEvidencia, String tipoContenido, int tamanoBytes, Instant creadaEn) {
    }

    record ReporteGuardadoDto(UUID idReporte, UUID idCiudadano, String tipoServicio, double latitud,
                              double longitud, String codigoMunicipio, String nombreMunicipio,
                              Instant inicioEstimado, String magnitud, String descripcion, String estado,
                              Instant fechaRegistro, Instant fechaRestablecimiento, Integer duracionMinutos,
                              int versionValidaciones, ConteoDto validacionCorte, ConteoDto validacionRestablecimiento,
                              List<EntradaDto> historia, List<EvidenciaDto> evidencias) {

        Reporte aDominio() {
            return new Reporte(idReporte, idCiudadano, TipoServicio.valueOf(tipoServicio),
                    new Ubicacion(latitud, longitud), new Municipio(codigoMunicipio, nombreMunicipio),
                    inicioEstimado, Magnitud.valueOf(magnitud), descripcion, EstadoReporte.valueOf(estado),
                    fechaRegistro, fechaRestablecimiento, duracionMinutos, versionValidaciones,
                    validacionCorte == null ? null : validacionCorte.aDominio(),
                    validacionRestablecimiento == null ? null : validacionRestablecimiento.aDominio(),
                    historia == null ? List.of() : historia.stream()
                            .map(h -> new Reporte.EntradaHistoria(EstadoReporte.valueOf(h.estado()), h.fecha(), h.detalle()))
                            .toList(),
                    evidencias == null ? List.of() : evidencias.stream()
                            .map(e -> new Reporte.Evidencia(e.idEvidencia(), e.tipoContenido(), e.tamanoBytes(), e.creadaEn()))
                            .toList());
        }
    }

    record EntradaDto(String estado, Instant fecha, String detalle) {
    }

    record PaginaDto(List<ReporteGuardadoDto> elementos, long total, int pagina, int tamano) {
    }

    record CercanoDto(UUID idReporte, String tipoServicio, String magnitud, String estado, Instant inicioEstimado,
                      double latitud, double longitud, int distanciaMetros, ConteoDto validacionCorte,
                      ConteoDto validacionRestablecimiento) {
        ReporteCercano aVista() {
            return new ReporteCercano(idReporte, TipoServicio.valueOf(tipoServicio), Magnitud.valueOf(magnitud),
                    EstadoReporte.valueOf(estado), inicioEstimado, new Ubicacion(latitud, longitud), distanciaMetros,
                    validacionCorte.aDominio(), validacionRestablecimiento.aDominio());
        }
    }

    record DenunciaDto(UUID idDenuncia, String tipoServicio, String codigoMunicipio, String estado,
                       Instant fechaRecepcion, boolean tieneEvidencia) {
    }
}
