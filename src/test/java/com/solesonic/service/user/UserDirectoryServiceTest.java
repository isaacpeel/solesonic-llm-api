package com.solesonic.service.user;

import com.solesonic.config.user.UserDirectorySettings;
import com.solesonic.model.user.UserDirectoryEntry;
import com.solesonic.repository.user.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserDirectoryServiceTest {
    private static final UUID CALLER = UUID.randomUUID();

    @Mock
    private UserProfileRepository userProfileRepository;

    private UserDirectoryService service(boolean enabled, int maxResults) {
        return new UserDirectoryService(userProfileRepository, new UserDirectorySettings(enabled, maxResults));
    }

    @Test
    void searchesForTheCallerExcludingThemselves() {
        UserDirectoryEntry dave = new UserDirectoryEntry(UUID.randomUUID(), "dave", "Dave");
        when(userProfileRepository.searchActive("dav", CALLER, 20)).thenReturn(List.of(dave));

        assertThat(service(true, 20).search(CALLER, "dav", 20)).containsExactly(dave);
    }

    @Test
    void entryHasNoEmailField() {
        assertThat(Arrays.stream(UserDirectoryEntry.class.getRecordComponents()).map(component -> component.getName()))
                .containsExactly("userId", "username", "displayName");
    }

    @Test
    void trimsTheQueryAndTreatsNullAsEmpty() {
        service(true, 20).search(CALLER, "  dav  ", 5);
        service(true, 20).search(CALLER, null, 5);

        verify(userProfileRepository).searchActive("dav", CALLER, 5);
        verify(userProfileRepository).searchActive("", CALLER, 5);
    }

    @Test
    void cutsALongQueryToSixtyFourCharacters() {
        service(true, 20).search(CALLER, "a".repeat(500), 5);

        verify(userProfileRepository).searchActive("a".repeat(64), CALLER, 5);
    }

    @Test
    void clampsTheLimitToTheConfiguredMaximumAndAtLeastOne() {
        service(true, 20).search(CALLER, "", 500);
        service(true, 20).search(CALLER, "", 0);
        service(true, 20).search(CALLER, "", null);

        verify(userProfileRepository, times(2)).searchActive("", CALLER, 20);
        verify(userProfileRepository).searchActive("", CALLER, 1);
    }

    @Test
    void escapesLikeWildcardsAndBackslashesInTheRepositoryQuery() {
        assertThat(UserProfileRepository.escapeLike("50%_off\\")).isEqualTo("50\\%\\_off\\\\");
        assertThat(UserProfileRepository.escapeLike("plain")).isEqualTo("plain");
    }

    @Test
    void doesNotQueryWhileDisabled() {
        UserDirectoryService disabled = service(false, 20);

        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.search(CALLER, "a", 5)).isEmpty();
        verifyNoInteractions(userProfileRepository);
    }

    @Test
    void dedupesByUsernameCaseInsensitivelyKeepingTheFirst() {
        UserDirectoryEntry first = new UserDirectoryEntry(UUID.randomUUID(), "Dave", "Dave");
        UserDirectoryEntry second = new UserDirectoryEntry(UUID.randomUUID(), "dave", "Other");
        when(userProfileRepository.searchActive(any(), any(), anyInt())).thenReturn(List.of(first, second));

        assertThat(service(true, 20).search(CALLER, "", 20)).containsExactly(first);
    }
}
