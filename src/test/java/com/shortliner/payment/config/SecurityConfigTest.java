package com.shortliner.payment.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {

    private final SecurityConfig config = new SecurityConfig();

    @Test
    void mapsKeycloakRealmRolesToSpringRoles() {
        Jwt jwt = jwt(Map.of("realm_access", Map.of("roles", List.of("user", "admin"))));

        assertThat(config.jwtAuthenticationConverter().convert(jwt).getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_user", "ROLE_admin");
    }

    @Test
    void tokenWithoutRealmRolesHasNoAuthorities() {
        Jwt jwt = jwt(Map.of("preferred_username", "someone"));

        assertThat(config.jwtAuthenticationConverter().convert(jwt).getAuthorities()).isEmpty();
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "none").subject("user-1");
        claims.forEach(builder::claim);
        return builder.build();
    }
}
