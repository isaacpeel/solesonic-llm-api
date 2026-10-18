package com.solesonic.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.UUID;

import static com.solesonic.security.JwtUserRequestFilter.SUB;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keycloak puts client roles in {@code roles} and group names in {@code groups}; both must reach
 * Spring Security, each under its own prefix.
 */
class RolesAndGroupsConverterTest {

    private static List<String> authorities(Jwt jwt) {
        return SecurityConfig.rolesAndGroupsConverter()
                .convert(jwt)
                .getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                // Spring Security adds its own FACTOR_BEARER for how the caller authenticated.
                .filter(authority -> authority.startsWith(SecurityConfig.ROLE) || authority.startsWith(SecurityConfig.GROUP))
                .toList();
    }

    private static Jwt.Builder token() {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim(SUB, UUID.randomUUID().toString());
    }

    @Test
    void mapsRolesAndGroupsUnderSeparatePrefixes() {
        Jwt jwt = token()
                .claim("roles", List.of("rag-admin", "model-select"))
                .claim("groups", List.of("izzy-bot-admin"))
                .build();

        assertThat(authorities(jwt)).containsExactlyInAnyOrder(
                "ROLE_rag-admin", "ROLE_model-select", "GROUP_izzy-bot-admin");
    }

    @Test
    void grantsNoGroupAuthorityWithoutAGroupsClaim() {
        Jwt jwt = token().claim("roles", List.of("rag-admin")).build();

        assertThat(authorities(jwt)).containsExactly("ROLE_rag-admin");
    }
}
