package co.edu.javeriana.servired.identidad.infraestructura;

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
import co.edu.javeriana.servired.identidad.infraestructura.persistencia.ClientePersistencia;
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

        assertThat(cliente.rotar("a".repeat(64), UUID.randomUUID(), "b".repeat(64), Instant.now(), correlacion)).isEmpty();
        servidor.verify();
    }

    @Test
    void reintentaAnteFallasYLuegoDeclaraNoDisponible() {
        servidor.expect(org.springframework.test.web.client.ExpectedCount.times(2),
                requestTo(URL + "/interno/v1/sesiones/cerrar")).andRespond(withServerError());

        assertThatThrownBy(() -> cliente.cerrar("a".repeat(64), correlacion)).isInstanceOf(PersistenciaNoDisponible.class);
    }
}
