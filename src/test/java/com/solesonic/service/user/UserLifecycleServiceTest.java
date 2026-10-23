package com.solesonic.service.user;

import com.solesonic.model.user.KeycloakAccount;
import com.solesonic.repository.user.UserLifecycleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserLifecycleServiceTest {
    private static final UUID ACTIVE_USER_ID = UUID.randomUUID();
    private static final UUID REMOVED_USER_ID = UUID.randomUUID();
    private static final UUID DISABLED_USER_ID = UUID.randomUUID();

    @Mock
    private KeycloakUserClient keycloakUserClient;

    @Mock
    private UserLifecycleRepository userLifecycleRepository;

    @Mock
    private UserProfileService userProfileService;

    private UserLifecycleService userLifecycleService;

    @BeforeEach
    void setUp() {
        userLifecycleService = new UserLifecycleService(keycloakUserClient, userLifecycleRepository, userProfileService, 30, 24);
    }

    @Test
    void recordsLiveAccountsAndMarksMissingOrDisabledOnesRemoved() {
        when(userLifecycleRepository.ownersToCheck(any())).thenReturn(List.of(ACTIVE_USER_ID, REMOVED_USER_ID, DISABLED_USER_ID));
        when(keycloakUserClient.find(ACTIVE_USER_ID)).thenReturn(Optional.of(new KeycloakAccount("sam", "sam@example.com", true)));
        when(keycloakUserClient.find(REMOVED_USER_ID)).thenReturn(Optional.empty());
        when(keycloakUserClient.find(DISABLED_USER_ID)).thenReturn(Optional.of(new KeycloakAccount("alex", null, false)));
        when(userLifecycleRepository.inactiveOwners(any())).thenReturn(List.of());

        UserLifecycleRun run = userLifecycleService.sweep();

        verify(userLifecycleRepository).recordFound(ACTIVE_USER_ID, "sam", "sam@example.com");
        verify(userLifecycleRepository).recordRemoved(REMOVED_USER_ID);
        verify(userLifecycleRepository).recordRemoved(DISABLED_USER_ID);
        verify(userProfileService).forget(REMOVED_USER_ID);
        assertThat(run).isEqualTo(new UserLifecycleRun(true, 3, 2, 0, 0));
    }

    /**
     * A realm that cannot be verified — wrong URI, missing role, Keycloak down — would answer 404 for
     * everyone, so nobody may be concluded removed from it. Dormancy needs no Keycloak and still runs.
     */
    @Test
    void anUnverifiableRealmMarksNobodyRemovedButStillPurgesTheDormant() {
        doThrow(new IllegalStateException("404")).when(keycloakUserClient).verifyRealm();
        when(userLifecycleRepository.inactiveOwners(any())).thenReturn(List.of(ACTIVE_USER_ID));
        when(userLifecycleRepository.purgeMemoryData(ACTIVE_USER_ID)).thenReturn(4);

        UserLifecycleRun run = userLifecycleService.sweep();

        verify(userLifecycleRepository, never()).ownersToCheck(any());
        verify(keycloakUserClient, never()).find(any());
        verify(userLifecycleRepository, never()).recordRemoved(any());
        assertThat(run).isEqualTo(new UserLifecycleRun(false, 0, 0, 1, 0));
    }

    @Test
    void aFailedLookupLeavesThatUserAsTheyWere() {
        when(userLifecycleRepository.ownersToCheck(any())).thenReturn(List.of(ACTIVE_USER_ID, REMOVED_USER_ID));
        when(keycloakUserClient.find(ACTIVE_USER_ID)).thenThrow(new IllegalStateException("Keycloak answered 503"));
        when(keycloakUserClient.find(REMOVED_USER_ID)).thenReturn(Optional.empty());
        when(userLifecycleRepository.inactiveOwners(any())).thenReturn(List.of());

        UserLifecycleRun run = userLifecycleService.sweep();

        verify(userLifecycleRepository, never()).recordRemoved(ACTIVE_USER_ID);
        verify(userLifecycleRepository).recordRemoved(REMOVED_USER_ID);
        assertThat(run).isEqualTo(new UserLifecycleRun(true, 1, 1, 0, 1));
    }

    @Test
    void oneFailedPurgeDoesNotStopTheRest() {
        when(userLifecycleRepository.ownersToCheck(any())).thenReturn(List.of());
        when(userLifecycleRepository.inactiveOwners(any())).thenReturn(List.of(REMOVED_USER_ID, DISABLED_USER_ID));
        when(userLifecycleRepository.purgeMemoryData(REMOVED_USER_ID)).thenThrow(new IllegalStateException("database down"));
        when(userLifecycleRepository.purgeMemoryData(DISABLED_USER_ID)).thenReturn(2);

        assertThat(userLifecycleService.sweep()).isEqualTo(new UserLifecycleRun(true, 0, 0, 1, 1));
    }
}
