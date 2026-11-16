package co.edu.javeriana.servired.reportes.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServiceUnavailable;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import co.edu.javeriana.servired.reportes.aplicacion.EventosReporte;
import co.edu.javeriana.servired.reportes.dominio.IdCorrelacion;
import co.edu.javeriana.servired.reportes.dominio.Magnitud;
import co.edu.javeriana.servired.reportes.dominio.NuevoReporte;
import co.edu.javeriana.servired.reportes.dominio.TipoServicio;
import co.edu.javeriana.servired.reportes.dominio.Ubicacion;
import co.edu.javeriana.servired.reportes.infraestructura.persistencia.ClientePersistencia;
import co.edu.javeriana.servired.reportes.infraestructura.persistencia.PersistenciaNoDisponible;
import co.edu.javeriana.servired.reportes.infraestructura.persistencia.PropiedadesPersistencia;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ClientePersistenciaTest {

    private static final String URL = "http://persistencia:8080";
    private final RestClient.Builder constructor = RestClient.builder();
    private final MockRestServiceServer servidor = MockRestServiceServer.bindTo(constructor).build();
    private final ClientePersistencia cliente = new ClientePersistencia(constructor,
            new PropiedadesPersistencia(URL, "clave-de-reportes-123", null, null, 3, Duration.ofMillis(1)),
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
    private final IdCorrelacion correlacion = IdCorrelacion.nuevo();

    private static final String GUARDADO = """
            {"idReporte":"%s","idCiudadano":"%s","tipoServicio":"ENERGIA","latitud":4.6,"longitud":-74.1,
             "codigoMunicipio":"11001","nombreMunicipio":"BOGOTÁ, D.C.","inicioEstimado":"2026-11-14T10:00:00Z",
             "magnitud":"CUADRA","estado":"RECIBIDO","fechaRegistro":"2026-11-14T10:05:00Z",
             "historia":[{"estado":"RECIBIDO","fecha":"2026-11-14T10:05:00Z","detalle":"Reporte recibido"}]}""";

    private NuevoReporte reporte() {
        return NuevoReporte.registrar(UUID.randomUUID(), TipoServicio.ENERGIA, new Ubicacion(4.6, -74.1),
                Instant.parse("2026-11-14T10:00:00Z"), Magnitud.CUADRA, null, Instant.parse("2026-11-14T10:05:00Z"));
    }

    @Test
    void enviaLaClaveDelServicioYLaCorrelacionYReintentaAnteFallas() {
        var r = reporte();
        servidor.expect(times(2), requestTo(URL + "/interno/v1/reportes/guardar"))
                .andRespond(withServiceUnavailable());
        servidor.expect(requestTo(URL + "/interno/v1/reportes/guardar"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Clave-Servicio", "clave-de-reportes-123"))
                .andExpect(header("X-Correlation-Id", correlacion.valor()))
                .andExpect(jsonPath("$.reporte.codigoMunicipio").value("11001"))
                .andExpect(jsonPath("$.evento.tipo").value("reporte.creado"))
                .andExpect(jsonPath("$.evento.correlationId").value(correlacion.valor()))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body(GUARDADO.formatted(r.idReporte(), r.idCiudadano())));

        var guardado = cliente.guardar(r, "11001", EventosReporte.reporteCreado(r, "11001", correlacion), correlacion);

        assertThat(guardado.idReporte()).isEqualTo(r.idReporte());
        assertThat(guardado.municipio().nombre()).isEqualTo("BOGOTÁ, D.C.");
        assertThat(guardado.historia()).hasSize(1);
        servidor.verify();
    }

    @Test
    void siPersistenciaSigueCaidaLanzaPersistenciaNoDisponible() {
        var r = reporte();
        servidor.expect(times(3), requestTo(URL + "/interno/v1/reportes/guardar"))
                .andRespond(withServiceUnavailable());

        assertThatThrownBy(() -> cliente.guardar(r, "11001",
                EventosReporte.reporteCreado(r, "11001", correlacion), correlacion))
                .isInstanceOf(PersistenciaNoDisponible.class);
    }

    @Test
    void unReporteInexistenteEsVacio() {
        UUID id = UUID.randomUUID();
        servidor.expect(requestTo(URL + "/interno/v1/reportes/" + id)).andRespond(withResourceNotFound());

        assertThat(cliente.buscar(id, correlacion)).isEmpty();
    }
}
