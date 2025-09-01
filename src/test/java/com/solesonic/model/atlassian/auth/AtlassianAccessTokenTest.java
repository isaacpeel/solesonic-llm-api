package com.solesonic.model.atlassian.auth;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtlassianAccessTokenTest {

    @Test
    void isExpired_whenTokenIsNotExpired_returnsFalse() {
        AtlassianAccessToken token = new AtlassianAccessToken();
        token.setCreated(ZonedDateTime.now().minusMinutes(5));
        token.setExpiresIn(3600); // 1 hour

        assertThat(token.isExpired()).isFalse();
    }

    @Test
    void isExpired_whenTokenIsExpired_returnsTrue() {
        AtlassianAccessToken token = new AtlassianAccessToken();
        token.setCreated(ZonedDateTime.now().minusHours(2));
        token.setExpiresIn(3600); // 1 hour

        assertThat(token.isExpired()).isTrue();
    }

    @Test
    void isExpired_whenTokenIsAtBoundaryWithBuffer_returnsTrue() {
        AtlassianAccessToken token = new AtlassianAccessToken();
        // Set created time so that token expires exactly 5 seconds ago (accounting for 10-second buffer)
        token.setCreated(ZonedDateTime.now().minusSeconds(3605)); // 3600 + 5 seconds ago
        token.setExpiresIn(3600);

        assertThat(token.isExpired()).isTrue();
    }

    @Test
    void isExpired_whenTokenIsJustWithinBuffer_returnsFalse() {
        AtlassianAccessToken token = new AtlassianAccessToken();
        // Set created time so that token is still within the 10-second buffer
        token.setCreated(ZonedDateTime.now().minusSeconds(3595)); // 3600 - 5 seconds ago
        token.setExpiresIn(3600);

        assertThat(token.isExpired()).isFalse();
    }

    @Test
    void isExpired_whenExpiresInIsNull_throwsException() {
        AtlassianAccessToken token = new AtlassianAccessToken();
        token.setCreated(ZonedDateTime.now());
        token.setExpiresIn(null);

        assertThatThrownBy(token::isExpired)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Token must have both expiresIn and created fields initialized.");
    }

    @Test
    void isExpired_whenCreatedIsNull_throwsException() {
        AtlassianAccessToken token = new AtlassianAccessToken();
        token.setCreated(null);
        token.setExpiresIn(3600);

        assertThatThrownBy(token::isExpired)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Token must have both expiresIn and created fields initialized.");
    }

    @Test
    void isExpired_whenBothFieldsAreNull_throwsException() {
        AtlassianAccessToken token = new AtlassianAccessToken();
        token.setCreated(null);
        token.setExpiresIn(null);

        assertThatThrownBy(token::isExpired)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Token must have both expiresIn and created fields initialized.");
    }

    @Test
    void isExpired_verifyBufferLogic() {
        AtlassianAccessToken token = new AtlassianAccessToken();
        
        // Test exactly at buffer boundary (should be expired)
        token.setCreated(ZonedDateTime.now().minusSeconds(3600 - 10)); // expires in exactly 10 seconds
        token.setExpiresIn(3600);
        assertThat(token.isExpired()).isTrue();
        
        // Test just before buffer boundary (should not be expired)
        token.setCreated(ZonedDateTime.now().minusSeconds(3600 - 11)); // expires in 11 seconds
        assertThat(token.isExpired()).isFalse();
    }
}