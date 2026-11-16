package co.edu.javeriana.servired.reportes.infraestructura.seguridad;

import co.edu.javeriana.servired.reportes.infraestructura.web.FiltroCorrelacion;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Reportes revalida el JWT aunque ya pasó por el gateway (defensa en
 * profundidad, decisión F): un servicio interno nunca confía en que la
 * petición llegó por el gateway.
 */
@Configuration
public class ConfiguracionSeguridad {

    @Bean
    SecurityFilterChain cadena(HttpSecurity http) throws Exception {
        return http
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/**", "/error").permitAll()
                        // Denuncias anónimas (fase 4): sin token, por diseño (ADR-09).
                        .requestMatchers(HttpMethod.POST, "/api/v1/denuncias", "/api/v1/denuncias/**").permitAll()
                        .requestMatchers("/api/v1/reportes", "/api/v1/reportes/**").hasRole("CIUDADANO")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.jwtAuthenticationConverter(convertidorRoles()))
                        .authenticationEntryPoint(noAutenticado())
                        .accessDeniedHandler(prohibido()))
                .exceptionHandling(e -> e.authenticationEntryPoint(noAutenticado()).accessDeniedHandler(prohibido()))
                .build();
    }

    @Bean
    JwtDecoder decodificador(@Value("${servired.jwt.jwks-url}") String jwksUrl,
                             @Value("${servired.jwt.emisor}") String emisor,
                             @Value("${servired.jwt.audiencia}") String audiencia) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUrl).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(emisor),
                new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(audiencia))));
        return decoder;
    }

    public static JwtAuthenticationConverter convertidorRoles() {
        var roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("rol");
        roles.setAuthorityPrefix("ROLE_");
        var convertidor = new JwtAuthenticationConverter();
        convertidor.setJwtGrantedAuthoritiesConverter(roles);
        return convertidor;
    }

    static AuthenticationEntryPoint noAutenticado() {
        return (p, r, e) -> escribir(r, 401, "No autenticado", "Se requiere un token de acceso válido");
    }

    static AccessDeniedHandler prohibido() {
        return (p, r, e) -> escribir(r, 403, "Prohibido", "Su rol no tiene permiso para esta operación");
    }

    private static void escribir(HttpServletResponse r, int estado, String titulo, String detalle) throws IOException {
        r.setStatus(estado);
        r.setContentType("application/problem+json");
        r.getWriter().write("{\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\",\"correlationId\":\"%s\"}"
                .formatted(titulo, estado, detalle, MDC.get(FiltroCorrelacion.CLAVE_MDC)));
    }
}
