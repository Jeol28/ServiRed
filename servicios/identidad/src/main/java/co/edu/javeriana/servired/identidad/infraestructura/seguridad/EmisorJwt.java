package co.edu.javeriana.servired.identidad.infraestructura.seguridad;

import co.edu.javeriana.servired.identidad.aplicacion.puertos.EmisorTokens;
import co.edu.javeriana.servired.identidad.dominio.Rol;
import co.edu.javeriana.servired.identidad.infraestructura.config.PropiedadesJwt;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JWT de acceso RS256. Claims: iss, aud, sub (id de la cuenta), rol, emp (empresa
 * del operador), iat, exp y jti. No incluye correo ni nombre (minimización de datos).
 */
public class EmisorJwt implements EmisorTokens {

    public static final String CLAIM_ROL = "rol";
    public static final String CLAIM_EMPRESA = "emp";

    private final ClavesFirma claves;
    private final PropiedadesJwt propiedades;
    private final Clock reloj;

    public EmisorJwt(ClavesFirma claves, PropiedadesJwt propiedades, Clock reloj) {
        this.claves = claves;
        this.propiedades = propiedades;
        this.reloj = reloj;
    }

    @Override
    public TokenAcceso emitir(UUID idCuenta, Rol rol, UUID idEmpresa) {
        Instant ahora = reloj.instant();
        Instant expira = ahora.plus(propiedades.duracionAcceso());
        var claims = new JWTClaimsSet.Builder()
                .issuer(propiedades.emisor())
                .audience(propiedades.audiencia())
                .subject(idCuenta.toString())
                .claim(CLAIM_ROL, rol.name())
                .issueTime(Date.from(ahora))
                .notBeforeTime(Date.from(ahora))
                .expirationTime(Date.from(expira))
                .jwtID(UUID.randomUUID().toString());
        if (idEmpresa != null) {
            claims.claim(CLAIM_EMPRESA, idEmpresa.toString());
        }
        var cabecera = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(claves.jwk().getKeyID()).type(JOSEObjectType.JWT).build();
        try {
            SignedJWT jwt = new SignedJWT(cabecera, claims.build());
            jwt.sign(new RSASSASigner(claves.jwk()));
            return new TokenAcceso(jwt.serialize(), expira);
        } catch (JOSEException e) {
            throw new IllegalStateException("No se pudo firmar el token", e);
        }
    }
}
