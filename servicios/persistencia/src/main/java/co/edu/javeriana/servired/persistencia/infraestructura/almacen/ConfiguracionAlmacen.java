package co.edu.javeriana.servired.persistencia.infraestructura.almacen;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

@Configuration
@EnableConfigurationProperties(PropiedadesAlmacen.class)
public class ConfiguracionAlmacen {

    private final PropiedadesAlmacen propiedades;

    public ConfiguracionAlmacen(PropiedadesAlmacen propiedades) {
        this.propiedades = propiedades;
    }

    @Bean
    AlmacenS3 almacenS3() {
        return new AlmacenS3(propiedades);
    }

    @EventListener(ApplicationReadyEvent.class)
    void prepararBucket() {
        intentarCrear(almacenS3());
    }

    /**
     * Readiness "almacen": el bucket de evidencias responde. En local, si aún no
     * existe (el almacén arrancó después), se vuelve a intentar crearlo.
     */
    @Bean
    HealthIndicator almacen(AlmacenS3 almacen) {
        return () -> {
            if (!almacen.disponible()) {
                intentarCrear(almacen);
            }
            return almacen.disponible() ? Health.up().build()
                    : Health.down().withDetail("bucket", propiedades.bucket()).build();
        };
    }

    private void intentarCrear(AlmacenS3 almacen) {
        if (!propiedades.crearBucket()) {
            return;
        }
        try {
            almacen.asegurarBucket();
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(ConfiguracionAlmacen.class)
                    .warn("el almacén de evidencias aún no responde: {}", e.getMessage());
        }
    }
}
