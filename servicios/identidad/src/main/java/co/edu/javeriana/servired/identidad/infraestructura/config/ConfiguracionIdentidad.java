package co.edu.javeriana.servired.identidad.infraestructura.config;

import co.edu.javeriana.servired.identidad.aplicacion.AdministracionCuentas;
import co.edu.javeriana.servired.identidad.aplicacion.Autenticacion;
import co.edu.javeriana.servired.identidad.aplicacion.puertos.CifradorClaves;
import co.edu.javeriana.servired.identidad.aplicacion.puertos.EmisorTokens;
import co.edu.javeriana.servired.identidad.aplicacion.puertos.RepositorioCuentas;
import co.edu.javeriana.servired.identidad.aplicacion.puertos.RepositorioSesiones;
import co.edu.javeriana.servired.identidad.infraestructura.persistencia.ClientePersistencia;
import co.edu.javeriana.servired.identidad.infraestructura.persistencia.PropiedadesPersistencia;
import co.edu.javeriana.servired.identidad.infraestructura.seguridad.CifradorBcrypt;
import co.edu.javeriana.servired.identidad.infraestructura.seguridad.ClavesFirma;
import co.edu.javeriana.servired.identidad.infraestructura.seguridad.EmisorJwt;
import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Composición: conecta los casos de uso (sin Spring) con sus adaptadores. */
@Configuration
@EnableConfigurationProperties({PropiedadesJwt.class, PropiedadesPersistencia.class})
public class ConfiguracionIdentidad {

    @Bean
    Clock reloj() {
        return Clock.systemUTC();
    }

    @Bean
    ClavesFirma clavesFirma(PropiedadesJwt propiedades) {
        return ClavesFirma.desde(propiedades.clavePrivada());
    }

    @Bean
    EmisorJwt emisorJwt(ClavesFirma claves, PropiedadesJwt propiedades, Clock reloj) {
        return new EmisorJwt(claves, propiedades, reloj);
    }

    @Bean
    CifradorBcrypt cifrador(@Value("${servired.bcrypt.costo:12}") int costo) {
        return new CifradorBcrypt(costo);
    }

    @Bean
    ClientePersistencia clientePersistencia(RestClient.Builder constructor, PropiedadesPersistencia propiedades,
                                            io.micrometer.core.instrument.MeterRegistry metricas) {
        var fabrica = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(propiedades.timeoutConexion()).build());
        fabrica.setReadTimeout(propiedades.timeoutLectura());
        return new ClientePersistencia(constructor.clone().requestFactory(fabrica), propiedades, metricas);
    }

    @Bean
    Autenticacion autenticacion(RepositorioCuentas cuentas, RepositorioSesiones sesiones, CifradorClaves c,
                                EmisorTokens e, Clock reloj, PropiedadesJwt jwt,
                                co.edu.javeriana.servired.identidad.aplicacion.puertos.RegistroAcciones acciones) {
        return new Autenticacion(cuentas, sesiones, c, e, reloj, jwt.duracionRefresco(), acciones);
    }

    @Bean
    AdministracionCuentas administracionCuentas(RepositorioCuentas cuentas, CifradorClaves c, Clock reloj) {
        return new AdministracionCuentas(cuentas, c, reloj);
    }
}
