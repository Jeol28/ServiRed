package co.edu.javeriana.servired.identidad.infraestructura.seguridad;

import co.edu.javeriana.servired.identidad.infraestructura.config.PropiedadesJwt;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Identidad valida sus propios tokens con su clave pública (sin llamarse a sí
 * misma). Sin estado ni cookies: no aplica CSRF. Las rutas no listadas se niegan.
 */
@Configuration
public class ConfiguracionSeguridad {

    @Bean
    SecurityFilterChain cadena(HttpSecurity http) throws Exception {
        return http
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/**", "/.well-known/jwks.json", "/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/registro", "/api/v1/auth/login",
                                "/api/v1/auth/refrescar", "/api/v1/auth/cerrar-sesion").permitAll()
                        .requestMatchers("/api/v1/cuentas/yo").authenticated()
                        .requestMatchers("/api/v1/cuentas", "/api/v1/cuentas/**").hasRole("ADMINISTRADOR")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.jwtAuthenticationConverter(convertidorRoles()))
                        .authenticationEntryPoint(RespuestasSeguridad.noAutenticado())
                        .accessDeniedHandler(RespuestasSeguridad.prohibido()))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(RespuestasSeguridad.noAutenticado())
                        .accessDeniedHandler(RespuestasSeguridad.prohibido()))
                .build();
    }

    @Bean
    JwtDecoder decodificador(ClavesFirma claves, PropiedadesJwt propiedades) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(claves.clavePublica()).build();
        OAuth2TokenValidator<Jwt> audiencia = new JwtClaimValidator<List<String>>("aud",
                aud -> aud != null && aud.contains(propiedades.audiencia()));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(propiedades.emisor()), audiencia));
        return decoder;
    }

    /** El claim "rol" se convierte en la autoridad ROLE_&lt;rol&gt;. */
    static JwtAuthenticationConverter convertidorRoles() {
        var roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("rol");
        roles.setAuthorityPrefix("ROLE_");
        var convertidor = new JwtAuthenticationConverter();
        convertidor.setJwtGrantedAuthoritiesConverter(roles);
        return convertidor;
    }
}
