package co.edu.javeriana.servired.persistencia.infraestructura.almacen;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.AlmacenEvidencias;
import java.net.URI;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Evidencias en un almacén compatible con S3: Supabase Storage (endpoint
 * {@code https://<proyecto>.supabase.co/storage/v1/s3}) en producción o
 * SeaweedFS en el entorno local. El bucket es privado: los clientes nunca
 * acceden directamente, siempre a través de Reportes y Persistencia.
 */
public class AlmacenS3 implements AlmacenEvidencias {

    private static final Logger log = LoggerFactory.getLogger(AlmacenS3.class);

    private final S3Client s3;
    private final String bucket;

    public AlmacenS3(PropiedadesAlmacen p) {
        this.bucket = p.bucket();
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(p.url()))
                .region(Region.of(p.region()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(p.acceso(), p.secreto())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClient(UrlConnectionHttpClient.builder().build())
                // Desde el SDK 2.30 los objetos se envían como aws-chunked con checksum al
                // final; los almacenes S3 compatibles (SeaweedFS, Supabase) no siempre lo
                // decodifican y guardan el archivo corrupto. Solo se calcula si es obligatorio.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
    }

    /**
     * Crea el bucket si no existe (solo entorno local; en Supabase se crea desde el panel).
     * Con varias réplicas arrancando a la vez, otra puede crearlo primero: no es un error.
     */
    public void asegurarBucket() {
        try {
            s3.headBucket(b -> b.bucket(bucket));
        } catch (NoSuchBucketException e) {
            crearBucket();
        } catch (S3Exception e) {
            if (e.statusCode() != 404) {
                throw e;
            }
            crearBucket();
        }
    }

    private void crearBucket() {
        try {
            s3.createBucket(b -> b.bucket(bucket));
            log.info("bucket de evidencias creado: {}", bucket);
        } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException e) {
            log.info("el bucket de evidencias ya lo creó otra réplica: {}", bucket);
        }
    }

    public boolean disponible() {
        try {
            s3.headBucket(b -> b.bucket(bucket));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void guardar(String clave, String tipoContenido, byte[] contenido) {
        s3.putObject(b -> b.bucket(bucket).key(clave).contentType(tipoContenido), RequestBody.fromBytes(contenido));
    }

    @Override
    public Optional<byte[]> leer(String clave) {
        try {
            return Optional.of(s3.getObjectAsBytes(b -> b.bucket(bucket).key(clave)).asByteArray());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    public void borrar(String clave) {
        try {
            s3.deleteObject(b -> b.bucket(bucket).key(clave));
        } catch (RuntimeException e) {
            log.warn("no se pudo borrar el objeto huérfano {}: {}", clave, e.getMessage());
        }
    }
}
