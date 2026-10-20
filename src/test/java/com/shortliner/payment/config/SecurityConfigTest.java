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

        assertThat(roles(jwt)).containsExactlyInAnyOrder("ROLE_user", "ROLE_admin");
    }

    @Test
    void tokenWithoutRealmRolesHasNoRoles() {
        Jwt jwt = jwt(Map.of("preferred_username", "someone"));

        assertThat(roles(jwt)).isEmpty();
    }

    /**
     * Spring Security 7 also adds a FACTOR_BEARER authority to every bearer
     * token (multi-factor support); only the roles mapped here are of interest.
     */
    private List<String> roles(Jwt jwt) {
        return config.jwtAuthenticationConverter().convert(jwt).getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> !authority.startsWith("FACTOR_"))
                .toList();
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "none").subject("user-1");
        claims.forEach(builder::claim);
        return builder.build();
    }
}
