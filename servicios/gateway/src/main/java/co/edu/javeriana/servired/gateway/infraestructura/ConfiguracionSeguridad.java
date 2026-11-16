package co.edu.javeriana.servired.gateway.infraestructura;

import co.edu.javeriana.servired.gateway.dominio.ReglasAcceso;
import co.edu.javeriana.servired.gateway.dominio.ReglasAcceso.Regla;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import reactor.core.publisher.Mono;

/**
 * Autenticación (JWT RS256 de Identidad, validado con su JWKS) y autorización
 * por rol según {@link ReglasAcceso}. Sin estado: no hay sesión ni CSRF.
 */
@Configuration
public class ConfiguracionSeguridad {

    @Bean
    SecurityWebFilterChain cadena(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(a -> {
                    // Actuator solo existe en el puerto de gestión (8081, no publicado).
                    a.pathMatchers("/actuator/**").permitAll();
                    for (Regla regla : ReglasAcceso.TABLA) {
                        String[] rutas = regla.rutas().toArray(String[]::new);
                        var acceso = regla.metodo() == null ? a.pathMatchers(rutas)
                                : a.pathMatchers(HttpMethod.valueOf(regla.metodo()), rutas);
                        switch (regla.acceso()) {
                            case PUBLICO -> acceso.permitAll();
                            case AUTENTICADO -> acceso.authenticated();
                            case ROLES -> acceso.hasAnyRole(regla.roles().toArray(String[]::new));
                        }
                    }
                    a.anyExchange().denyAll();
                })
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.jwtAuthenticationConverter(convertidorRoles()))
                        .authenticationEntryPoint(noAutenticado())
                        .accessDeniedHandler(prohibido()))
                .exceptionHandling(e -> e.authenticationEntryPoint(noAutenticado()).accessDeniedHandler(prohibido()))
                .build();
    }

    @Bean
    ReactiveJwtDecoder decodificador(@Value("${servired.jwt.jwks-url}") String jwksUrl,
                                     @Value("${servired.jwt.emisor}") String emisor,
                                     @Value("${servired.jwt.audiencia}") String audiencia) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwksUrl).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(emisor),
                new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(audiencia))));
        return decoder;
    }

    static Converter<Jwt, Mono<AbstractAuthenticationToken>> convertidorRoles() {
        var roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("rol");
        roles.setAuthorityPrefix("ROLE_");
        var convertidor = new JwtAuthenticationConverter();
        convertidor.setJwtGrantedAuthoritiesConverter(roles);
        return new ReactiveJwtAuthenticationConverterAdapter(convertidor);
    }

    static ServerAuthenticationEntryPoint noAutenticado() {
        return (exchange, e) -> {
            exchange.getResponse().getHeaders().set("WWW-Authenticate", "Bearer");
            return Problemas.escribir(exchange, HttpStatus.UNAUTHORIZED, "No autenticado",
                    "Se requiere un token de acceso válido");
        };
    }

    static ServerAccessDeniedHandler prohibido() {
        return (exchange, e) -> Problemas.escribir(exchange, HttpStatus.FORBIDDEN, "Prohibido",
                "Su rol no tiene permiso para esta operación");
    }
}
