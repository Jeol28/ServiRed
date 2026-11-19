package co.edu.javeriana.servired.persistencia.soporte;

import java.nio.file.Path;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

/**
 * PostgreSQL 17 con PostGIS, igual que el compose (infra/postgres/Dockerfile): las
 * migraciones cargan los polígonos municipales (ADR-27). La imagen se construye una vez y
 * Docker la reutiliza de su caché.
 */
public final class BaseDePrueba {

    private static final String IMAGEN = new ImageFromDockerfile("servired/postgres-postgis-prueba", false)
            .withFileFromPath(".", Path.of("../../infra/postgres"))
            .get();

    private BaseDePrueba() {
    }

    @SuppressWarnings("resource")
    public static PostgreSQLContainer<?> crear() {
        return new PostgreSQLContainer<>(DockerImageName.parse(IMAGEN).asCompatibleSubstituteFor("postgres"));
    }
}
