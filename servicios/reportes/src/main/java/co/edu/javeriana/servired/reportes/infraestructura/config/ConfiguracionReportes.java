package co.edu.javeriana.servired.reportes.infraestructura.config;

import co.edu.javeriana.servired.reportes.aplicacion.ConsultarReporte;
import co.edu.javeriana.servired.reportes.aplicacion.CrearReporte;
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
@EnableConfigurationProperties(PropiedadesPersistencia.class)
public class ConfiguracionReportes {

    @Bean
    Clock reloj() {
        return Clock.systemUTC();
    }

    @Bean
    ClientePersistencia clientePersistencia(RestClient.Builder constructor, PropiedadesPersistencia propiedades) {
        var fabrica = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(propiedades.timeoutConexion()).build());
        fabrica.setReadTimeout(propiedades.timeoutLectura());
        return new ClientePersistencia(constructor.clone().requestFactory(fabrica), propiedades);
    }

    @Bean
    CrearReporte crearReporte(ClientePersistencia persistencia, Clock reloj) {
        return new CrearReporte(persistencia, persistencia, reloj);
    }

    @Bean
    ConsultarReporte consultarReporte(ClientePersistencia persistencia) {
        return new ConsultarReporte(persistencia);
    }
}
