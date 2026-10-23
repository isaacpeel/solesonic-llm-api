package com.solesonic.service.user;

import com.solesonic.repository.user.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private UserProfileRepository userProfileRepository;

    @Test
    void recordsTheGivenNameBeforeTheFullName() {
        new UserProfileService(userProfileRepository).record(USER_ID, jwt(Map.of("given_name", " Sam ", "name", "Sam Smith")));

        verify(userProfileRepository).saveDisplayName(USER_ID, "Sam");
    }

    @Test
    void fallsBackToTheFullName() {
        new UserProfileService(userProfileRepository).record(USER_ID, jwt(Map.of("name", "Sam Smith")));

        verify(userProfileRepository).saveDisplayName(USER_ID, "Sam Smith");
    }

    @Test
    void writesAnUnchangedNameOnce() {
        UserProfileService userProfileService = new UserProfileService(userProfileRepository);

        userProfileService.record(USER_ID, jwt(Map.of("given_name", "Sam")));
        userProfileService.record(USER_ID, jwt(Map.of("given_name", "Sam")));

        verify(userProfileRepository, times(1)).saveDisplayName(USER_ID, "Sam");
    }

    @Test
    void aTokenWithNoNameWritesNothing() {
        new UserProfileService(userProfileRepository).record(USER_ID, jwt(Map.of("email", "sam@example.com")));

        verify(userProfileRepository, never()).saveDisplayName(any(), anyString());
    }

    @Test
    void aFailedWriteNeverFailsTheRequest() {
        doThrow(new IllegalStateException("database down")).when(userProfileRepository).saveDisplayName(USER_ID, "Sam");

        assertThatCode(() -> new UserProfileService(userProfileRepository).record(USER_ID, jwt(Map.of("given_name", "Sam"))))
                .doesNotThrowAnyException();
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(USER_ID.toString())
                .claims(existing -> existing.putAll(claims))
                .build();
    }
}
