package co.edu.javeriana.servired.reportes.contrato;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.javeriana.servired.reportes.aplicacion.EventosReporte;
import co.edu.javeriana.servired.reportes.aplicacion.SeguimientoDeCasos;
import co.edu.javeriana.servired.reportes.aplicacion.SobreEvento;
import co.edu.javeriana.servired.reportes.dominio.IdCorrelacion;
import co.edu.javeriana.servired.reportes.dominio.Magnitud;
import co.edu.javeriana.servired.reportes.dominio.NuevaDenuncia;
import co.edu.javeriana.servired.reportes.dominio.NuevoReporte;
import co.edu.javeriana.servired.reportes.dominio.ObjetoValidacion;
import co.edu.javeriana.servired.reportes.dominio.ReglasValidacion;
import co.edu.javeriana.servired.reportes.dominio.Reporte;
import co.edu.javeriana.servired.reportes.dominio.SentidoValidacion;
import co.edu.javeriana.servired.reportes.dominio.TipoServicio;
import co.edu.javeriana.servired.reportes.dominio.Ubicacion;
import co.edu.javeriana.servired.reportes.infraestructura.mensajeria.ConsumidorEventosCasos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Reportes contra el contrato de eventos (contratos/eventos):
 * - como productor, cada evento que arma, serializado como viaja (Jackson con la
 *   configuración de Spring Boot), cumple su JSON Schema;
 * - como consumidor, procesa los ejemplos del contrato de caso.actualizado y caso.cerrado
 *   (que produce Atención, en .NET), también los mínimos sin campos opcionales.
 */
class ContratoEventosTest {

    /**
     * Jackson SIN la configuración de Spring Boot (que apaga WRITE_DATES_AS_TIMESTAMPS): el
     * caso más desfavorable. El evento debe cumplir el contrato igual.
     */
    private static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules().build();
    /** El de Spring Boot, el que usa la app. */
    private static final ObjectMapper JSON_BOOT = Jackson2ObjectMapperBuilder.json().build();
    private static final IdCorrelacion CORRELACION = IdCorrelacion.nuevo();
    private static final Instant AHORA = Instant.parse("2026-11-18T15:04:05.123Z");
    private static final Ubicacion BOGOTA = new Ubicacion(4.6287, -74.0648);

    private static NuevoReporte reporte(String descripcion) {
        return NuevoReporte.registrar(UUID.randomUUID(), TipoServicio.ACUEDUCTO, BOGOTA,
                Instant.parse("2026-11-18T13:00:00Z"), Magnitud.BARRIO, descripcion, AHORA);
    }

    static Stream<SobreEvento> eventosProducidos() {
        var denuncia = NuevaDenuncia.recibir(TipoServicio.ENERGIA, new Ubicacion(10.9685, -74.7813),
                "Conexión directa al poste de la esquina", AHORA);
        return Stream.of(
                EventosReporte.reporteCreado(reporte("Sin agua en todo el barrio"), "11001", CORRELACION),
                EventosReporte.reporteCreado(reporte(null), "11001", CORRELACION),
                EventosReporte.restablecimientoRegistrado(UUID.randomUUID(),
                        new Reporte.Restablecimiento(AHORA, 270, "Volvió el agua"), CORRELACION, AHORA),
                EventosReporte.validacionRegistrada(UUID.randomUUID(), new ReglasValidacion.Resultado(
                        ObjetoValidacion.CORTE, SentidoValidacion.CONFIRMA, new Reporte.Conteo(3, 0), true),
                        CORRELACION, AHORA),
                EventosReporte.validacionRegistrada(UUID.randomUUID(), new ReglasValidacion.Resultado(
                        ObjetoValidacion.RESTABLECIMIENTO, SentidoValidacion.CONTROVIERTE, new Reporte.Conteo(1, 2),
                        false), CORRELACION, AHORA),
                EventosReporte.denunciaRecibida(denuncia, "08001", true, CORRELACION),
                EventosReporte.denunciaRecibida(denuncia, "08001", false, CORRELACION));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("eventosProducidos")
    void cadaEventoQueProduceCumpleSuEsquema(SobreEvento evento) throws Exception {
        for (ObjectMapper mapper : new ObjectMapper[]{JSON, JSON_BOOT}) {
            JsonNode comoViaja = mapper.readTree(mapper.writeValueAsBytes(evento));
            assertThat(ContratoEventos.errores(comoViaja, evento.tipo() + ".v" + evento.version())).isEmpty();
        }
    }

    // ------------------------------------------------------------------ consumidor

    private final SeguimientoDeCasos seguimiento = mock(SeguimientoDeCasos.class);
    private final Channel canal = mock(Channel.class);
    private final ConsumidorEventosCasos consumidor = new ConsumidorEventosCasos(seguimiento, JSON,
            mock(RabbitTemplate.class), 5, new SimpleMeterRegistry());

    private static Message mensaje(JsonNode sobre) throws Exception {
        var p = new MessageProperties();
        p.setReceivedRoutingKey(sobre.path("tipo").asText() + ".v1");
        p.setDeliveryTag(7);
        return new Message(JSON.writeValueAsBytes(sobre), p);
    }

    static Stream<String> variantes() {
        return ContratoEventos.variantes();
    }

    @ParameterizedTest(name = "caso.actualizado {0}")
    @MethodSource("variantes")
    void procesaCasoActualizadoDelContrato(String variante) throws Exception {
        JsonNode sobre = ContratoEventos.ejemplo("caso.actualizado", variante);
        when(seguimiento.aplicar(any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        consumidor.recibir(mensaje(sobre), canal);

        verify(seguimiento).aplicar(eq("caso.actualizado"), eq(UUID.fromString(sobre.path("idEvento").asText())),
                eq(UUID.fromString(sobre.path("datos").path("idReporte").asText())),
                eq(sobre.path("datos").path("estado").asText()), eq(null),
                eq(Instant.parse(sobre.path("fechaOcurrencia").asText())), any());
        verify(canal).basicAck(7, false);
        verify(canal, never()).basicReject(anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @ParameterizedTest(name = "caso.cerrado {0}")
    @MethodSource("variantes")
    void procesaCasoCerradoDelContrato(String variante) throws Exception {
        JsonNode sobre = ContratoEventos.ejemplo("caso.cerrado", variante);
        when(seguimiento.aplicar(any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        consumidor.recibir(mensaje(sobre), canal);

        String resolucion = sobre.path("datos").has("resolucion") ? sobre.path("datos").path("resolucion").asText()
                : null;
        verify(seguimiento).aplicar(eq("caso.cerrado"), any(),
                eq(UUID.fromString(sobre.path("datos").path("idReporte").asText())), any(), eq(resolucion), any(),
                any());
        verify(canal).basicAck(7, false);
    }

    @Test
    void losEjemplosDelContratoSonValidos() {
        for (String tipo : new String[]{"caso.actualizado", "caso.cerrado"}) {
            ContratoEventos.variantes().forEach(v ->
                    assertThat(ContratoEventos.errores(ContratoEventos.ejemplo(tipo, v), tipo + ".v1"))
                            .as(tipo + " " + v).isEmpty());
        }
    }
}
