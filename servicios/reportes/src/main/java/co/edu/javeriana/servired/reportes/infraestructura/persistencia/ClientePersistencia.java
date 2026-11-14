package co.edu.javeriana.servired.reportes.infraestructura.persistencia;

import co.edu.javeriana.servired.reportes.aplicacion.SobreEvento;
import co.edu.javeriana.servired.reportes.aplicacion.puertos.CatalogoTerritorial;
import co.edu.javeriana.servired.reportes.aplicacion.puertos.RepositorioReportes;
import co.edu.javeriana.servired.reportes.dominio.EstadoReporte;
import co.edu.javeriana.servired.reportes.dominio.IdCorrelacion;
import co.edu.javeriana.servired.reportes.dominio.Magnitud;
import co.edu.javeriana.servired.reportes.dominio.Municipio;
import co.edu.javeriana.servired.reportes.dominio.NuevoReporte;
import co.edu.javeriana.servired.reportes.dominio.Reporte;
import co.edu.javeriana.servired.reportes.dominio.TipoServicio;
import co.edu.javeriana.servired.reportes.dominio.Ubicacion;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Adaptador hacia la API interna de Persistencia (contratos/internos/persistencia.v1.yaml).
 * Reintenta ante fallas de red o 5xx: es seguro porque cada operación es
 * idempotente por X-Correlation-Id.
 */
public class ClientePersistencia implements RepositorioReportes, CatalogoTerritorial {

    private static final Logger log = LoggerFactory.getLogger(ClientePersistencia.class);
    private static final String CABECERA_CORRELACION = IdCorrelacion.CABECERA;

    private final RestClient http;
    private final PropiedadesPersistencia propiedades;

    public ClientePersistencia(RestClient.Builder constructor, PropiedadesPersistencia propiedades) {
        this.propiedades = propiedades;
        this.http = constructor.clone()
                .baseUrl(propiedades.url())
                .defaultHeader("X-Clave-Servicio", propiedades.clave())
                .build();
    }

    @Override
    public Municipio resolverMunicipio(Ubicacion u, IdCorrelacion correlacion) {
        var m = conReintentos("resolverMunicipio", () -> http.get()
                .uri("/interno/v1/catalogo/municipio-cercano?latitud={lat}&longitud={lon}", u.latitud(), u.longitud())
                .header(CABECERA_CORRELACION, correlacion.valor())
                .retrieve()
                .body(MunicipioDto.class));
        return new Municipio(m.codigo(), m.nombre());
    }

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
        try {
            return Optional.of(conReintentos("consultarReporte", () -> http.get()
                    .uri("/interno/v1/reportes/{id}", idReporte)
                    .header(CABECERA_CORRELACION, correlacion.valor())
                    .retrieve()
                    .body(ReporteGuardadoDto.class)).aDominio());
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    private <T> T conReintentos(String operacion, Supplier<T> llamada) {
        RuntimeException ultima = null;
        for (int intento = 1; intento <= propiedades.intentos(); intento++) {
            try {
                return llamada.get();
            } catch (ResourceAccessException | HttpServerErrorException e) {
                ultima = e;
                log.warn("persistencia falló en {} (intento {}/{}): {}", operacion, intento,
                        propiedades.intentos(), e.getMessage());
                esperar(intento);
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                    throw e;
                }
                // 4xx distinto de 404: error de integración (clave, contrato), no del ciudadano.
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

    record ReporteGuardadoDto(UUID idReporte, UUID idCiudadano, String tipoServicio, double latitud,
                              double longitud, String codigoMunicipio, String nombreMunicipio,
                              Instant inicioEstimado, String magnitud, String descripcion, String estado,
                              Instant fechaRegistro, List<EntradaDto> historia) {

        Reporte aDominio() {
            return new Reporte(idReporte, idCiudadano, TipoServicio.valueOf(tipoServicio),
                    new Ubicacion(latitud, longitud), new Municipio(codigoMunicipio, nombreMunicipio),
                    inicioEstimado, Magnitud.valueOf(magnitud), descripcion, EstadoReporte.valueOf(estado),
                    fechaRegistro, historia == null ? List.of() : historia.stream()
                    .map(h -> new Reporte.EntradaHistoria(EstadoReporte.valueOf(h.estado()), h.fecha(), h.detalle()))
                    .toList());
        }
    }

    record EntradaDto(String estado, Instant fecha, String detalle) {
    }
}
