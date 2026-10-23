package com.solesonic.service.user;

import com.solesonic.model.user.UserProfile;
import com.solesonic.repository.user.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();

    @Mock
    private UserProfileRepository userProfileRepository;

    @Test
    void recordsTheUsernameEmailAndName() {
        new UserProfileService(userProfileRepository).record(USER_ID,
                jwt(Map.of("preferred_username", "sam", "email", " sam@example.com ", "given_name", "Sam")));

        verify(userProfileRepository).save(new UserProfile(USER_ID, "sam", "sam@example.com", "Sam"));
    }

    @Test
    void recordsTheGivenNameBeforeTheFullName() {
        new UserProfileService(userProfileRepository).record(USER_ID, jwt(Map.of("given_name", " Sam ", "name", "Sam Smith")));

        verify(userProfileRepository).save(new UserProfile(USER_ID, null, null, "Sam"));
    }

    @Test
    void fallsBackToTheFullName() {
        new UserProfileService(userProfileRepository).record(USER_ID, jwt(Map.of("name", "Sam Smith")));

        verify(userProfileRepository).save(new UserProfile(USER_ID, null, null, "Sam Smith"));
    }

    /**
     * A token with a username but no name is still worth recording, now that the row holds more than
     * the name.
     */
    @Test
    void recordsAUsernameWithoutAName() {
        new UserProfileService(userProfileRepository).record(USER_ID, jwt(Map.of("preferred_username", "sam")));

        verify(userProfileRepository).save(new UserProfile(USER_ID, "sam", null, null));
    }

    @Test
    void writesAnUnchangedProfileOnce() {
        UserProfileService userProfileService = new UserProfileService(userProfileRepository);

        userProfileService.record(USER_ID, jwt(Map.of("preferred_username", "sam", "given_name", "Sam")));
        userProfileService.record(USER_ID, jwt(Map.of("preferred_username", "sam", "given_name", "Sam")));

        verify(userProfileRepository, times(1)).save(any());
    }

    @Test
    void writesAgainWhenOnlyTheEmailChanged() {
        UserProfileService userProfileService = new UserProfileService(userProfileRepository);

        userProfileService.record(USER_ID, jwt(Map.of("preferred_username", "sam", "email", "sam@example.com")));
        userProfileService.record(USER_ID, jwt(Map.of("preferred_username", "sam", "email", "sam@example.org")));

        verify(userProfileRepository).save(new UserProfile(USER_ID, "sam", "sam@example.org", null));
    }

    @Test
    void aTokenWithNoProfileClaimsWritesNothing() {
        new UserProfileService(userProfileRepository).record(USER_ID, jwt(Map.of("scope", "openid")));

        verify(userProfileRepository, never()).save(any());
    }

    @Test
    void aFailedWriteNeverFailsTheRequest() {
        doThrow(new IllegalStateException("database down")).when(userProfileRepository).save(any());

        assertThatCode(() -> new UserProfileService(userProfileRepository).record(USER_ID, jwt(Map.of("given_name", "Sam"))))
                .doesNotThrowAnyException();
    }

    @Test
    void readsUsernamesInOneQueryLeavingOutUsersWithNone() {
        when(userProfileRepository.findAllById(Set.of(USER_ID, OTHER_USER_ID)))
                .thenReturn(List.of(new UserProfile(USER_ID, "sam", null, "Sam"),
                        new UserProfile(OTHER_USER_ID, null, null, "Alex")));

        assertThat(new UserProfileService(userProfileRepository).usernames(List.of(USER_ID, OTHER_USER_ID, USER_ID)))
                .containsExactly(Map.entry(USER_ID, "sam"));
    }

    @Test
    void aFailedReadIsNoProfiles() {
        when(userProfileRepository.findAllById(any())).thenThrow(new IllegalStateException("database down"));

        assertThat(new UserProfileService(userProfileRepository).profiles(List.of(USER_ID))).isEmpty();
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(USER_ID.toString())
                .claims(existing -> existing.putAll(claims))
                .build();
    }
}
