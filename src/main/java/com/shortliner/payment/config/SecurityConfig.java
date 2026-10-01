package com.shortliner.payment.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * OAuth2 resource server for Keycloak access tokens relayed by
 * shortliner-gateway. The gateway already rejects anonymous calls to
 * /api/payment/**, but it isn't the only possible caller inside the
 * cluster, so this service enforces authentication itself as well.
 * <p>
 * Authorization rules live here as URL rules rather than @PreAuthorize: an
 * AccessDeniedException thrown from inside a controller would be caught by
 * GlobalExceptionHandler's catch-all and turned into a 500.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter)
            throws Exception {
        http
                // Bearer tokens aren't attached by browsers automatically, so
                // there's no CSRF surface here; the gateway handles CSRF for
                // the cookie session.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // K8s probes and Prometheus scrape these unauthenticated
                        // (on the management port in the cluster).
                        .requestMatchers(EndpointRequest.to("health", "prometheus")).permitAll()
                        .requestMatchers(EndpointRequest.toAnyEndpoint()).hasRole("admin")
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/api-docs/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        // Future payment-provider webhooks won't carry a JWT —
                        // the provider calls us directly. Such a path goes here
                        // as permitAll(), and its controller must verify the
                        // provider's request signature instead, e.g.:
                        //   .requestMatchers(HttpMethod.POST, "/api/payments/webhooks/**").permitAll()
                        .requestMatchers("/api/payments/_debug/**").hasRole("admin")
                        .requestMatchers("/api/payments/**").authenticated()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }

    /**
     * Built from the JWKS URI rather than issuer-uri: issuer-uri makes Spring
     * fetch discovery from keycloak.local at startup, which doesn't resolve
     * from pods. The JWKS itself is fetched lazily on the first token, so the
     * app starts (and tests run) without Keycloak. iss is still validated.
     */
    @Bean
    JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
                          @Value("${keycloak.issuer-uri}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }

    /** Maps Keycloak's realm_access.roles to ROLE_&lt;name&gt; authorities. */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
            if (realmAccess == null || !(realmAccess.get("roles") instanceof Collection<?> roles)) {
                return List.of();
            }
            return roles.stream()
                    .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();
        });
        return converter;
    }
}
