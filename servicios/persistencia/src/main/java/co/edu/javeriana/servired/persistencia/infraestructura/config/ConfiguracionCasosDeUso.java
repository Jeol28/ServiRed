package co.edu.javeriana.servired.persistencia.infraestructura.config;

import co.edu.javeriana.servired.persistencia.aplicacion.AbrirCaso;
import co.edu.javeriana.servired.persistencia.aplicacion.ConsultarReporte;
import co.edu.javeriana.servired.persistencia.aplicacion.GestionCuentas;
import co.edu.javeriana.servired.persistencia.aplicacion.GestionSesiones;
import co.edu.javeriana.servired.persistencia.aplicacion.GuardarReporte;
import co.edu.javeriana.servired.persistencia.aplicacion.ResolverMunicipio;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.CatalogoTerritorial;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.Outbox;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RegistroIdempotencia;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioCasos;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioCuentas;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioReportes;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioSesiones;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.UnidadDeTrabajo;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Composición: conecta los casos de uso (sin Spring) con sus adaptadores. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ClavesServicio.class)
public class ConfiguracionCasosDeUso {

    @Bean
    GuardarReporte guardarReporte(UnidadDeTrabajo t, RegistroIdempotencia i, RepositorioReportes r,
                                  CatalogoTerritorial c, Outbox o) {
        return new GuardarReporte(t, i, r, c, o);
    }

    @Bean
    ConsultarReporte consultarReporte(RepositorioReportes r) {
        return new ConsultarReporte(r);
    }

    @Bean
    AbrirCaso abrirCaso(UnidadDeTrabajo t, RegistroIdempotencia i, RepositorioCasos r, CatalogoTerritorial c) {
        return new AbrirCaso(t, i, r, c);
    }

    @Bean
    Clock reloj() {
        return Clock.systemUTC();
    }

    @Bean
    GestionCuentas gestionCuentas(UnidadDeTrabajo t, RegistroIdempotencia i, RepositorioCuentas c,
                                  RepositorioSesiones s) {
        return new GestionCuentas(t, i, c, s);
    }

    @Bean
    GestionSesiones gestionSesiones(UnidadDeTrabajo t, RepositorioSesiones s, RepositorioCuentas c, Clock reloj) {
        return new GestionSesiones(t, s, c, reloj);
    }

    @Bean
    ResolverMunicipio resolverMunicipio(CatalogoTerritorial c) {
        return new ResolverMunicipio(c);
    }
}
