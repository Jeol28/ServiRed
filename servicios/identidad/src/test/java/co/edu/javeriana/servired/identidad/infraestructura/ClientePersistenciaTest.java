package co.edu.javeriana.servired.identidad.infraestructura;

import co.edu.javeriana.servired.identidad.aplicacion.puertos.EmisorTokens;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import co.edu.javeriana.servired.identidad.dominio.Correo;
import co.edu.javeriana.servired.identidad.dominio.DatosInvalidos;
import co.edu.javeriana.servired.identidad.dominio.Excepciones.CorreoYaRegistrado;
import co.edu.javeriana.servired.identidad.dominio.IdCorrelacion;
import co.edu.javeriana.servired.identidad.dominio.NuevaCuenta;
import co.edu.javeriana.servired.identidad.dominio.Rol;
import co.edu.javeriana.servired.identidad.infraestructura.persistencia.CircuitoPersistencia;
import co.edu.javeriana.servired.identidad.infraestructura.persistencia.ClientePersistencia;
import co.edu.javeriana.servired.identidad.infraestructura.persistencia.PropiedadesCircuito;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import co.edu.javeriana.servired.identidad.infraestructura.persistencia.PersistenciaNoDisponible;
import co.edu.javeriana.servired.identidad.infraestructura.persistencia.PropiedadesPersistencia;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ClientePersistenciaTest {

    private static final String URL = "http://persistencia:8080";
    private final RestClient.Builder constructor = RestClient.builder();
    private final MockRestServiceServer servidor = MockRestServiceServer.bindTo(constructor).build();
    private final ClientePersistencia cliente = new ClientePersistencia(constructor,
            new PropiedadesPersistencia(URL, "clave-identidad-123456", null, null, 2, Duration.ofMillis(1)));
    private final IdCorrelacion correlacion = IdCorrelacion.nuevo();

    private NuevaCuenta nueva() {
        return NuevaCuenta.ciudadano("ana@ejemplo.co", "clave-segura-1", null, true, Instant.now());
    }

    @Test
    void buscaCredencialesConElCorreoEnElCuerpoYNoEnLaUrl() {
        servidor.expect(requestTo(URL + "/interno/v1/cuentas/credenciales"))
                .andExpect(header("X-Clave-Servicio", "clave-identidad-123456"))
                .andExpect(content().json("{\"correo\":\"ana@ejemplo.co\"}"))
                .andRespond(withResourceNotFound());

        assertThat(cliente.credenciales(Correo.de("ana@ejemplo.co"), correlacion)).isEmpty();
    }

    @Test
    void traduceLosConflictosDelRegistro() {
        servidor.expect(once(), requestTo(URL + "/interno/v1/cuentas/registrar"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"detail\":\"El correo ya está registrado\"}"));
        servidor.expect(once(), requestTo(URL + "/interno/v1/cuentas/registrar"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"detail\":\"Viola una regla de integridad\"}"));

        assertThatThrownBy(() -> cliente.registrar(nueva(), "h", correlacion)).isInstanceOf(CorreoYaRegistrado.class);
        var operador = NuevaCuenta.personal("op@ejemplo.co", "clave-segura-1", null, Rol.OPERADOR, UUID.randomUUID(),
                Instant.now());
        assertThatThrownBy(() -> cliente.registrar(operador, "h", correlacion)).isInstanceOf(DatosInvalidos.class);
    }

    @Test
    void unaRotacionRechazadaEsVaciaYNoSeReintenta() {
        servidor.expect(once(), requestTo(URL + "/interno/v1/sesiones/rotar"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"motivo\":\"REUTILIZADA\"}"));

        var r = cliente.rotar("a".repeat(64), UUID.randomUUID(), "b".repeat(64), Instant.now(), new EmisorTokens.Plan(UUID.randomUUID(), Instant.now(), Instant.now().plusSeconds(900)), correlacion);
        assertThat(r.cuenta()).isEmpty();
        assertThat(r.reutilizado()).isTrue();
        servidor.verify();
    }

    @Test
    void laRotacionEnviaElJtiDelAccesoYLaListaDeRevocadosLlegaCompleta() {
        var plan = new EmisorTokens.Plan(UUID.randomUUID(), Instant.now(), Instant.now().plusSeconds(900));
        servidor.expect(once(), requestTo(URL + "/interno/v1/sesiones/rotar"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.jtiAcceso")
                        .value(plan.jti().toString()))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"motivo\":\"EXPIRADA\"}"));
        servidor.expect(once(), requestTo(URL + "/interno/v1/sesiones/accesos-revocados?desde=2026-11-18T12:00:00Z"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "[{\"jti\":\"0b1d8f4e-6f3a-4c55-9d2e-1f0a7c9e2b11\",\"expiraEn\":\"2026-11-18T12:15:00Z\","
                                + "\"revocadoEn\":\"2026-11-18T12:01:00Z\"}]", MediaType.APPLICATION_JSON));

        cliente.rotar("a".repeat(64), UUID.randomUUID(), "b".repeat(64), Instant.now(), plan, correlacion);
        var revocados = cliente.accesosRevocados(Instant.parse("2026-11-18T12:00:00Z"), correlacion);

        assertThat(revocados).singleElement().satisfies(r -> {
            assertThat(r.jti()).isEqualTo(UUID.fromString("0b1d8f4e-6f3a-4c55-9d2e-1f0a7c9e2b11"));
            assertThat(r.expiraEn()).isEqualTo(Instant.parse("2026-11-18T12:15:00Z"));
        });
        servidor.verify();
    }

    @Test
    void reintentaAnteFallasYLuegoDeclaraNoDisponible() {
        servidor.expect(org.springframework.test.web.client.ExpectedCount.times(2),
                requestTo(URL + "/interno/v1/sesiones/cerrar")).andRespond(withServerError());

        assertThatThrownBy(() -> cliente.cerrar("a".repeat(64), correlacion)).isInstanceOf(PersistenciaNoDisponible.class);
    }

    @Test
    void conElCircuitoAbiertoNiSiquieraLaRotacionLlegaAPersistencia() {
        var c = RestClient.builder();
        var srv = MockRestServiceServer.bindTo(c).build();
        var circuito = CircuitoPersistencia.crear(new PropiedadesCircuito(2, 2, 50, Duration.ofHours(1), 1));
        var conCircuito = new ClientePersistencia(c,
                new PropiedadesPersistencia(URL, "clave-identidad-123456", null, null, 2, Duration.ofMillis(1)),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), circuito);
        srv.expect(org.springframework.test.web.client.ExpectedCount.times(2),
                requestTo(URL + "/interno/v1/sesiones/cerrar")).andRespond(withServerError());
        assertThatThrownBy(() -> conCircuito.cerrar("a".repeat(64), correlacion))
                .isInstanceOf(PersistenciaNoDisponible.class);
        assertThat(circuito.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Abierto: el inicio de sesión y la rotación fallan sin llamar a Persistencia (503).
        assertThatThrownBy(() -> conCircuito.rotar("a".repeat(64), UUID.randomUUID(), "b".repeat(64),
                Instant.now(), new EmisorTokens.Plan(UUID.randomUUID(), Instant.now(), Instant.now().plusSeconds(900)), correlacion)).isInstanceOf(PersistenciaNoDisponible.class);
        srv.verify();
    }

    @Test
    void laCorrelacionDeOtraSolicitudNoSeDisfrazaDeCorreoRepetido() {
        servidor.expect(once(), requestTo(URL + "/interno/v1/cuentas/registrar"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"motivo\":\"CORRELACION_AJENA\",\"detail\":\"El X-Correlation-Id ya se usó\"}"));

        assertThatThrownBy(() -> cliente.registrar(nueva(), "h", correlacion))
                .isInstanceOf(co.edu.javeriana.servired.identidad.dominio.Excepciones.CorrelacionAjena.class);
    }
}
