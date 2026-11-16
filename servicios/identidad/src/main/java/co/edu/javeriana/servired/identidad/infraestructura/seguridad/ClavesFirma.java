package co.edu.javeriana.servired.identidad.infraestructura.seguridad;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Par de claves RSA con el que Identidad firma los JWT (RS256). La clave
 * pública se publica como JWKS para que el gateway y los servicios validen
 * los tokens sin compartir secretos. El {@code kid} es la huella RFC 7638.
 */
public final class ClavesFirma {

    private static final Logger log = LoggerFactory.getLogger(ClavesFirma.class);

    private final RSAKey jwk;

    private ClavesFirma(RSAKey jwk) {
        this.jwk = jwk;
    }

    public static ClavesFirma desde(String clavePrivadaBase64) {
        try {
            RSAPrivateCrtKey privada;
            RSAPublicKey publica;
            KeyFactory fabrica = KeyFactory.getInstance("RSA");
            if (clavePrivadaBase64 == null || clavePrivadaBase64.isBlank()) {
                log.warn("IDENTIDAD_JWT_CLAVE_PRIVADA vacía: se usa una clave efímera (los tokens no sobreviven a un reinicio)");
                var generador = KeyPairGenerator.getInstance("RSA");
                generador.initialize(2048);
                var par = generador.generateKeyPair();
                privada = (RSAPrivateCrtKey) par.getPrivate();
                publica = (RSAPublicKey) par.getPublic();
            } else {
                byte[] der = Base64.getDecoder().decode(clavePrivadaBase64.strip());
                privada = (RSAPrivateCrtKey) fabrica.generatePrivate(new PKCS8EncodedKeySpec(der));
                publica = (RSAPublicKey) fabrica.generatePublic(
                        new RSAPublicKeySpec(privada.getModulus(), privada.getPublicExponent()));
            }
            RSAKey sinKid = new RSAKey.Builder(publica).privateKey(privada).build();
            return new ClavesFirma(new RSAKey.Builder(publica).privateKey(privada)
                    .keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256)
                    .keyID(sinKid.computeThumbprint().toString()).build());
        } catch (JOSEException | java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("IDENTIDAD_JWT_CLAVE_PRIVADA no es una clave RSA PKCS#8 en Base64", e);
        }
    }

    public RSAKey jwk() {
        return jwk;
    }

    /** Solo la parte pública, para /.well-known/jwks.json. */
    public JWKSet jwksPublico() {
        return new JWKSet(jwk.toPublicJWK());
    }

    public RSAPublicKey clavePublica() {
        try {
            return jwk.toRSAPublicKey();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
