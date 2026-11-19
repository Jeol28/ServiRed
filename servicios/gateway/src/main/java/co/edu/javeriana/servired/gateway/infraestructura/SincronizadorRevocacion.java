package co.edu.javeriana.servired.gateway.infraestructura;

import co.edu.javeriana.servired.gateway.dominio.ListaRevocacion;
import co.edu.javeriana.servired.gateway.dominio.ListaRevocacion.Entrada;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Mantiene al día la {@link ListaRevocacion} consultando a Identidad (ADR-26): carga completa
 * al arrancar y luego, cada {@code intervalo}, solo lo revocado desde la última consulta.
 *
 * <p>Si Identidad no responde, conserva la lista que tiene (no rechaza todo el tráfico): los
 * tokens de acceso duran minutos y la alerta {@code ListaDeRevocacionDesactualizada} avisa.
 */
@Component
public class SincronizadorRevocacion {

    private static final Logger log = LoggerFactory.getLogger(SincronizadorRevocacion.class);

    private final ListaRevocacion lista;
    private final String url;
    private final Duration solapamiento;
    private final Clock reloj;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Instant inicio;
    private final Counter fallas;
    private volatile boolean fallando;

    public SincronizadorRevocacion(ListaRevocacion lista, MeterRegistry metricas,
                                   @Value("${servired.jwt.revocacion.url}") String url,
                                   @Value("${servired.jwt.revocacion.solapamiento:PT30S}") Duration solapamiento) {
        this.lista = lista;
        this.url = url;
        this.solapamiento = solapamiento;
        this.reloj = Clock.systemUTC();
        this.inicio = reloj.instant();
        this.fallas = Counter.builder("servired.revocacion.sincronizaciones.fallidas")
                .description("Consultas a Identidad por la lista de revocación que fallaron").register(metricas);
        Gauge.builder("servired.revocacion.tokens", lista, ListaRevocacion::tamano)
                .description("Tokens de acceso revocados y aún vigentes que el gateway rechaza").register(metricas);
        Gauge.builder("servired.revocacion.antiguedad", this, SincronizadorRevocacion::antiguedad)
                .baseUnit("segundos")
                .description("Segundos desde la última sincronización exitosa de la lista de revocación")
                .register(metricas);
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "${servired.jwt.revocacion.intervalo:PT5S}")
    public void sincronizar() {
        Instant desde = lista.proximoDesde(solapamiento);
        String destino = desde == null ? url
                : url + "?desde=" + URLEncoder.encode(desde.toString(), StandardCharsets.UTF_8);
        try {
            var respuesta = http.send(HttpRequest.newBuilder(URI.create(destino)).timeout(Duration.ofSeconds(3))
                    .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (respuesta.statusCode() != 200) {
                throw new IllegalStateException("Identidad respondió " + respuesta.statusCode());
            }
            JsonNode cuerpo = json.readTree(respuesta.body());
            JsonNode revocados = cuerpo.path("revocados");
            if (!cuerpo.hasNonNull("generadaEn") || !revocados.isArray()) {
                throw new IllegalStateException("respuesta sin generadaEn ni revocados");
            }
            var entradas = new ArrayList<Entrada>();
            revocados.forEach(r -> entradas.add(new Entrada(r.path("jti").asText(),
                    Instant.parse(r.path("expiraEn").asText()))));
            lista.aplicar(entradas, Instant.parse(cuerpo.get("generadaEn").asText()), reloj.instant());
            if (fallando) {
                log.info("lista de revocación sincronizada de nuevo ({} tokens)", lista.tamano());
                fallando = false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            fallas.increment();
            if (!fallando) {
                log.warn("no se pudo sincronizar la lista de revocación; se conserva la anterior: {}", e.getMessage());
                fallando = true;
            }
        }
    }

    private double antiguedad() {
        Instant ultima = lista.ultimaSincronizacion();
        return Duration.between(ultima == null ? inicio : ultima, reloj.instant()).toMillis() / 1000.0;
    }
}
