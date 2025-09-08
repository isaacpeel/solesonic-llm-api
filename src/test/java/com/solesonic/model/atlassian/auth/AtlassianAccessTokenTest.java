package com.solesonic.model.atlassian.auth;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtlassianAccessTokenTest {

    @Test
    void isExpired_whenTokenIsNotExpired_returnsFalse() {
        AtlassianAccessToken token = new AtlassianAccessToken(
                null, null, null, null, null, 3600, false, 
                ZonedDateTime.now().minusMinutes(5), null, null, null);

        assertThat(token.isExpired()).isFalse();
    }

    @Test
    void isExpired_whenTokenIsExpired_returnsTrue() {
        AtlassianAccessToken token = new AtlassianAccessToken(
                null, null, null, null, null, 3600, false, 
                ZonedDateTime.now().minusHours(2), null, null, null);

        assertThat(token.isExpired()).isTrue();
    }

    @Test
    void isExpired_whenTokenIsAtBoundaryWithBuffer_returnsTrue() {
        AtlassianAccessToken token = new AtlassianAccessToken(
                null, null, null, null, null, 3600, false, 
                ZonedDateTime.now().minusSeconds(3605), null, null, null); // 3600 + 5 seconds ago

        assertThat(token.isExpired()).isTrue();
    }

    @Test
    void isExpired_whenExpiresInIsNull_throwsException() {
        AtlassianAccessToken token = new AtlassianAccessToken(
                null, null, null, null, null, null, false, 
                ZonedDateTime.now(), null, null, null);

        assertThatThrownBy(token::isExpired)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Token must have both expiresIn and created fields initialized.");
    }

    @Test
    void isExpired_whenCreatedIsNull_throwsException() {
        AtlassianAccessToken token = new AtlassianAccessToken(
                null, null, null, null, null, 3600, false, 
                null, null, null, null);

        assertThatThrownBy(token::isExpired)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Token must have both expiresIn and created fields initialized.");
    }

    @Test
    void isExpired_whenBothFieldsAreNull_throwsException() {
        AtlassianAccessToken token = new AtlassianAccessToken(
                null, null, null, null, null, null, false, 
                null, null, null, null);

        assertThatThrownBy(token::isExpired)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Token must have both expiresIn and created fields initialized.");
    }

    @Test
    void isExpired_verifyBufferLogic() {
        // Test exactly at buffer boundary (should be expired)
        AtlassianAccessToken token1 = new AtlassianAccessToken(
                null, null, null, null, null, 3600, false, 
                ZonedDateTime.now().minusSeconds(3600 - 10), null, null, null); // expires in exactly 10 seconds
        assertThat(token1.isExpired()).isTrue();
        
        // Test just before buffer boundary (should not be expired)
        AtlassianAccessToken token2 = new AtlassianAccessToken(
                null, null, null, null, null, 3600, false, 
                ZonedDateTime.now().minusSeconds(3600 - 11), null, null, null); // expires in 11 seconds
        assertThat(token2.isExpired()).isFalse();
    }
}