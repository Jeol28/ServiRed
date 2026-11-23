package co.edu.javeriana.servired.persistencia.infraestructura.config;

import co.edu.javeriana.servired.persistencia.infraestructura.anclas.PublicadorAnclasGitHub;
import co.edu.javeriana.servired.persistencia.infraestructura.anclas.PropiedadesAnclas;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.PublicadorAnclas;
import co.edu.javeriana.servired.persistencia.aplicacion.AnclasAuditoria;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.CodificadorSegmentos;
import co.edu.javeriana.servired.persistencia.aplicacion.ArchivoAuditoria;
import co.edu.javeriana.servired.persistencia.aplicacion.AbrirCaso;
import co.edu.javeriana.servired.persistencia.aplicacion.ConsultarReporte;
import co.edu.javeriana.servired.persistencia.aplicacion.GestionCuentas;
import co.edu.javeriana.servired.persistencia.aplicacion.GestionEvidencias;
import co.edu.javeriana.servired.persistencia.aplicacion.OperacionesAtencion;
import co.edu.javeriana.servired.persistencia.aplicacion.OperacionesAuditoria;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioAuditoria;
import co.edu.javeriana.servired.persistencia.aplicacion.OperacionesIndicadores;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioIndicadores;
import co.edu.javeriana.servired.persistencia.aplicacion.OperacionesReporte;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioAtencion;
import co.edu.javeriana.servired.persistencia.aplicacion.LimpiezaAlmacen;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.AlmacenEvidencias;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioFase4;
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
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Composición: conecta los casos de uso (sin Spring) con sus adaptadores. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({ClavesServicio.class, PropiedadesAnclas.class})
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
    AbrirCaso abrirCaso(UnidadDeTrabajo t, RegistroIdempotencia i, RepositorioCasos r, CatalogoTerritorial c,
                        Outbox o) {
        return new AbrirCaso(t, i, r, c, o);
    }

    @Bean
    LimpiezaAlmacen limpiezaAlmacen(AlmacenEvidencias a, RepositorioFase4 r, Clock reloj,
            @Value("${servired.almacen.limpieza.gracia:PT1H}") Duration gracia,
            @Value("${servired.almacen.limpieza.freno-minimo:5}") int frenoMinimo,
            @Value("${servired.almacen.limpieza.freno-proporcion:0.2}") double frenoProporcion) {
        return new LimpiezaAlmacen(a, r, reloj, gracia, frenoMinimo, frenoProporcion);
    }

    @Bean
    ArchivoAuditoria archivoAuditoria(UnidadDeTrabajo t, RepositorioAuditoria r, AlmacenEvidencias a,
            CodificadorSegmentos c, Clock reloj,
            @Value("${servired.auditoria.archivo.meses-en-base:12}") int mesesEnBase,
            @Value("${servired.auditoria.archivo.maximo-por-segmento:50000}") int maximoPorSegmento) {
        return new ArchivoAuditoria(t, r, a, c, reloj, mesesEnBase, maximoPorSegmento);
    }

    @Bean
    PublicadorAnclas publicadorAnclas(PropiedadesAnclas propiedades) {
        return new PublicadorAnclasGitHub(propiedades);
    }

    @Bean
    AnclasAuditoria anclasAuditoria(RepositorioAuditoria r, AlmacenEvidencias a, CodificadorSegmentos c,
                                    PublicadorAnclas p, Clock reloj) {
        return new AnclasAuditoria(r, a, c, p, reloj);
    }

    @Bean
    Clock reloj() {
        return Clock.systemUTC();
    }

    @Bean
    GestionCuentas gestionCuentas(UnidadDeTrabajo t, RegistroIdempotencia i, RepositorioCuentas c,
                                  RepositorioSesiones s, Outbox o) {
        return new GestionCuentas(t, i, c, s, o);
    }

    @Bean
    GestionSesiones gestionSesiones(UnidadDeTrabajo t, RepositorioSesiones s, RepositorioCuentas c, Clock reloj) {
        return new GestionSesiones(t, s, c, reloj);
    }

    @Bean
    OperacionesReporte operacionesReporte(UnidadDeTrabajo t, RegistroIdempotencia i, RepositorioReportes r,
                                          RepositorioFase4 f, Outbox o) {
        return new OperacionesReporte(t, i, r, f, o);
    }

    @Bean
    GestionEvidencias gestionEvidencias(UnidadDeTrabajo t, RegistroIdempotencia i, RepositorioReportes r,
                                        RepositorioFase4 f, CatalogoTerritorial c, AlmacenEvidencias a, Outbox o,
                                        Clock reloj) {
        return new GestionEvidencias(t, i, r, f, c, a, o, reloj);
    }

    @Bean
    OperacionesAtencion operacionesAtencion(UnidadDeTrabajo t, RegistroIdempotencia i, RepositorioAtencion r,
                                            AlmacenEvidencias a, Outbox o) {
        return new OperacionesAtencion(t, i, r, a, o);
    }

    @Bean
    OperacionesIndicadores operacionesIndicadores(UnidadDeTrabajo t, RepositorioIndicadores r) {
        return new OperacionesIndicadores(t, r);
    }

    @Bean
    OperacionesAuditoria operacionesAuditoria(UnidadDeTrabajo t, RepositorioAuditoria r, Outbox o) {
        return new OperacionesAuditoria(t, r, o);
    }

    @Bean
    ResolverMunicipio resolverMunicipio(CatalogoTerritorial c) {
        return new ResolverMunicipio(c);
    }
}
