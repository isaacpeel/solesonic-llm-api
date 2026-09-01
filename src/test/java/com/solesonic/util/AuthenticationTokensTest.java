package com.solesonic.util;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthenticationTokensTest {

    @Test
    void token_withJwtPrincipal_returnsTheRawTokenValue() {
        Jwt jwt = Jwt.withTokenValue("token-abc")
                .header("alg", "none")
                .claim("sub", "a-user")
                .issuedAt(Instant.EPOCH)
                .expiresAt(Instant.EPOCH.plusSeconds(3600))
                .build();

        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(jwt);

        assertThat(AuthenticationTokens.token(authentication)).isEqualTo("token-abc");
    }

    /**
     * Every caller is about to forward this token to another service, so an unusable principal has
     * to fail here rather than several hops away as an opaque 401.
     */
    @Test
    void token_withNonJwtPrincipal_throwsIllegalStateException() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn("not-a-jwt");

        assertThatThrownBy(() -> AuthenticationTokens.token(authentication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT");
    }
}
