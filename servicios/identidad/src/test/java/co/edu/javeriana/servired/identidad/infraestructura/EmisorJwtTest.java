package co.edu.javeriana.servired.identidad.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.javeriana.servired.identidad.dominio.Rol;
import co.edu.javeriana.servired.identidad.infraestructura.config.PropiedadesJwt;
import co.edu.javeriana.servired.identidad.infraestructura.seguridad.ClavesFirma;
import co.edu.javeriana.servired.identidad.infraestructura.seguridad.EmisorJwt;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmisorJwtTest {

    private static final java.time.Instant CREADA = java.time.Instant.parse("2026-11-01T10:00:00Z");

    private final PropiedadesJwt propiedades = new PropiedadesJwt("servired-identidad", "servired-api",
            Duration.ofMinutes(15), Duration.ofDays(14), null);

    @Test
    void firmaUnTokenVerificableConElJwksPublicoYSinDatosPersonales() throws Exception {
        ClavesFirma claves = ClavesFirma.desde("");
        UUID cuenta = UUID.randomUUID();
        UUID empresa = UUID.randomUUID();

        String token = new EmisorJwt(claves, propiedades, Clock.systemUTC()).emitir(cuenta, Rol.OPERADOR, empresa, CREADA).valor();

        SignedJWT jwt = SignedJWT.parse(token);
        RSAKey publica = (RSAKey) claves.jwksPublico().getKeyByKeyId(jwt.getHeader().getKeyID());
        assertThat(publica.isPrivate()).isFalse();
        assertThat(jwt.verify(new RSASSAVerifier(publica))).isTrue();
        var claims = jwt.getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo(cuenta.toString());
        assertThat(claims.getLongClaim(EmisorJwt.CLAIM_CREADA)).as("antigüedad de la cuenta (ADR-22)")
                .isEqualTo(CREADA.getEpochSecond());
        assertThat(claims.getIssuer()).isEqualTo("servired-identidad");
        assertThat(claims.getAudience()).containsExactly("servired-api");
        assertThat(claims.getStringClaim("rol")).isEqualTo("OPERADOR");
        assertThat(claims.getStringClaim("emp")).isEqualTo(empresa.toString());
        assertThat(claims.getClaims()).doesNotContainKeys("correo", "email", "nombre");
        assertThat(Duration.between(claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant()))
                .isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void cargaLaClaveDesdeBase64ConKidEstable() throws Exception {
        var generador = KeyPairGenerator.getInstance("RSA");
        generador.initialize(2048);
        String base64 = Base64.getEncoder().encodeToString(generador.generateKeyPair().getPrivate().getEncoded());

        assertThat(ClavesFirma.desde(base64).jwk().getKeyID()).isEqualTo(ClavesFirma.desde(base64).jwk().getKeyID());
    }
}
