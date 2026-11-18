package co.edu.javeriana.servired.reportes.infraestructura.config;

import co.edu.javeriana.servired.reportes.infraestructura.persistencia.CircuitoPersistencia;
import co.edu.javeriana.servired.reportes.infraestructura.persistencia.PropiedadesCircuito;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import co.edu.javeriana.servired.reportes.aplicacion.ConsultarReporte;
import co.edu.javeriana.servired.reportes.aplicacion.CrearReporte;
import co.edu.javeriana.servired.reportes.aplicacion.GestionEvidencias;
import co.edu.javeriana.servired.reportes.aplicacion.ParticipacionCiudadana;
import co.edu.javeriana.servired.reportes.aplicacion.RecibirDenuncia;
import co.edu.javeriana.servired.reportes.aplicacion.SeguimientoDeCasos;
import co.edu.javeriana.servired.reportes.aplicacion.puertos.CatalogoTerritorial;
import co.edu.javeriana.servired.reportes.aplicacion.puertos.RepositorioReportes;
import co.edu.javeriana.servired.reportes.aplicacion.puertos.SaneadorImagenes;
import co.edu.javeriana.servired.reportes.dominio.ReglasValidacion;
import co.edu.javeriana.servired.reportes.infraestructura.imagenes.SaneadorImagenesImageIO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import co.edu.javeriana.servired.reportes.infraestructura.persistencia.ClientePersistencia;
import co.edu.javeriana.servired.reportes.infraestructura.persistencia.PropiedadesPersistencia;
import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Composición: conecta los casos de uso (sin Spring) con sus adaptadores. */
@Configuration
@EnableConfigurationProperties({PropiedadesPersistencia.class, PropiedadesCircuito.class})
public class ConfiguracionReportes {

    @Bean
    Clock reloj() {
        return Clock.systemUTC();
    }

    /** Circuit breaker hacia Persistencia (ADR-20); su estado se publica en /actuator/prometheus. */
    @Bean
    CircuitBreaker circuitoPersistencia(PropiedadesCircuito propiedades, MeterRegistry metricas) {
        var registro = CircuitBreakerRegistry.ofDefaults();
        var circuito = CircuitoPersistencia.crear(registro, propiedades);
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registro).bindTo(metricas);
        return circuito;
    }

    @Bean
    ClientePersistencia clientePersistencia(RestClient.Builder constructor, PropiedadesPersistencia propiedades,
                                            ObjectMapper json, CircuitBreaker circuitoPersistencia) {
        var fabrica = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(propiedades.timeoutConexion()).build());
        fabrica.setReadTimeout(propiedades.timeoutLectura());
        return new ClientePersistencia(constructor.clone().requestFactory(fabrica), propiedades, json,
                circuitoPersistencia);
    }

    @Bean
    CrearReporte crearReporte(CatalogoTerritorial catalogo, RepositorioReportes repositorio, Clock reloj) {
        return new CrearReporte(catalogo, repositorio, reloj);
    }

    @Bean
    ConsultarReporte consultarReporte(RepositorioReportes repositorio) {
        return new ConsultarReporte(repositorio);
    }

    @Bean
    ReglasValidacion reglasValidacion(@Value("${servired.validacion.umbral:3}") int umbral,
                                      @Value("${servired.validacion.radio-metros:1000}") int radioMetros) {
        return new ReglasValidacion(umbral, radioMetros);
    }

    @Bean
    SaneadorImagenesImageIO saneadorImagenes() {
        return new SaneadorImagenesImageIO();
    }

    @Bean
    ParticipacionCiudadana participacionCiudadana(RepositorioReportes repositorio, ReglasValidacion reglas,
                                                  Clock reloj) {
        return new ParticipacionCiudadana(repositorio, reglas, reloj);
    }

    @Bean
    GestionEvidencias gestionEvidencias(RepositorioReportes repositorio, SaneadorImagenes saneador) {
        return new GestionEvidencias(repositorio, saneador);
    }

    @Bean
    SeguimientoDeCasos seguimientoDeCasos(RepositorioReportes repositorio) {
        return new SeguimientoDeCasos(repositorio);
    }

    @Bean
    RecibirDenuncia recibirDenuncia(CatalogoTerritorial catalogo, RepositorioReportes repositorio,
                                    SaneadorImagenes saneador, Clock reloj) {
        return new RecibirDenuncia(catalogo, repositorio, saneador, reloj);
    }
}
