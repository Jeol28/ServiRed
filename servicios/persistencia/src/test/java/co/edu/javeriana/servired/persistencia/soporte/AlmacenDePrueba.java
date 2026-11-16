package co.edu.javeriana.servired.persistencia.soporte;

import java.time.Duration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** SeaweedFS con la gateway S3, igual que en el compose. */
public final class AlmacenDePrueba {

    public static final String IMAGEN = "chrislusf/seaweedfs:3.97";

    private AlmacenDePrueba() {
    }

    @SuppressWarnings("resource")
    public static GenericContainer<?> crear() {
        return new GenericContainer<>(IMAGEN)
                .withExposedPorts(8333)
                .withEnv("ALMACEN_S3_ACCESO", "acceso-prueba")
                .withEnv("ALMACEN_S3_SECRETO", "secreto-prueba")
                .withCopyToContainer(org.testcontainers.utility.MountableFile.forHostPath(
                        "../../infra/almacen/iniciar-almacen.sh"), "/iniciar-almacen.sh")
                .withCreateContainerCmdModifier(c -> c.withEntrypoint("/bin/sh", "/iniciar-almacen.sh"))
                .waitingFor(Wait.forHttp("/").forPort(8333).forStatusCodeMatching(c -> c < 500)
                        .withStartupTimeout(Duration.ofSeconds(90)));
    }

    public static void registrar(GenericContainer<?> almacen, DynamicPropertyRegistry r) {
        r.add("servired.almacen.url", () -> "http://" + almacen.getHost() + ":" + almacen.getMappedPort(8333));
        r.add("servired.almacen.acceso", () -> "acceso-prueba");
        r.add("servired.almacen.secreto", () -> "secreto-prueba");
        r.add("servired.almacen.bucket", () -> "evidencias");
    }
}
