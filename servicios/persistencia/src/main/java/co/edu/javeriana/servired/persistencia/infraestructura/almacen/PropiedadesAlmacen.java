package co.edu.javeriana.servired.persistencia.infraestructura.almacen;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Almacén S3 de evidencias (ALMACEN_S3_* en .env). Solo Persistencia tiene estas credenciales. */
@ConfigurationProperties(prefix = "servired.almacen")
public record PropiedadesAlmacen(String url, String region, String acceso, String secreto, String bucket,
                                 boolean crearBucket) {
}
