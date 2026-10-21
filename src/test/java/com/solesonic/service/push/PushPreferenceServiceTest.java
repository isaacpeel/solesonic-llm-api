package com.solesonic.service.push;

import com.solesonic.exception.push.PushException;
import com.solesonic.model.push.PushErrorCode;
import com.solesonic.model.push.PushPreference;
import com.solesonic.model.push.PushPreferenceRequest;
import com.solesonic.model.push.PushPreferenceSummary;
import com.solesonic.repository.push.PushPreferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PushPreferenceServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private PushPreferenceRepository pushPreferenceRepository;

    private PushPreferenceService pushPreferenceService;

    @BeforeEach
    void setUp() {
        pushPreferenceService = new PushPreferenceService(pushPreferenceRepository, Clock.fixed(NOW, ZoneOffset.UTC));

        when(pushPreferenceRepository.save(any(PushPreference.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void aUserWithNoStoredPreferencesHasEverythingOn() {
        when(pushPreferenceRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThat(pushPreferenceService.get(USER_ID)).isEqualTo(new PushPreferenceSummary(true, true, true, true));
    }

    @Test
    void returnsTheStoredPreferences() {
        when(pushPreferenceRepository.findById(USER_ID)).thenReturn(Optional.of(stored(false, true, true, false)));

        assertThat(pushPreferenceService.get(USER_ID)).isEqualTo(new PushPreferenceSummary(false, true, true, false));
    }

    /**
     * A client that predates the interest fields sends only the first two; what it does not know
     * about must stay exactly as it was.
     */
    @Test
    void aPutWithoutTheInterestFieldsLeavesThemUnchanged() {
        when(pushPreferenceRepository.findById(USER_ID)).thenReturn(Optional.of(stored(true, true, false, false)));

        PushPreferenceSummary summary = pushPreferenceService.update(USER_ID, new PushPreferenceRequest(false, false, null, null));

        assertThat(summary).isEqualTo(new PushPreferenceSummary(false, false, false, false));
    }

    @Test
    void aPutWithoutTheInterestFieldsForAUserWithNoRowLeavesThemOn() {
        when(pushPreferenceRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThat(pushPreferenceService.update(USER_ID, new PushPreferenceRequest(true, false, null, null)))
                .isEqualTo(new PushPreferenceSummary(true, false, true, true));
    }

    @Test
    void storesTheInterestFieldsWhenGiven() {
        when(pushPreferenceRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThat(pushPreferenceService.update(USER_ID, new PushPreferenceRequest(true, true, false, true)))
                .isEqualTo(new PushPreferenceSummary(true, true, false, true));
    }

    @Test
    void storesBothPreferences() {
        PushPreferenceSummary summary = pushPreferenceService.update(USER_ID, new PushPreferenceRequest(false, true, null, null));

        assertThat(summary).isEqualTo(new PushPreferenceSummary(false, true, true, true));

        ArgumentCaptor<PushPreference> saved = ArgumentCaptor.forClass(PushPreference.class);
        verify(pushPreferenceRepository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getValue().isTurnComplete()).isFalse();
        assertThat(saved.getValue().isElicitation()).isTrue();
        assertThat(saved.getValue().getUpdated()).isEqualTo(NOW.atZone(ZoneOffset.UTC));
    }

    /**
     * A missing field is refused rather than defaulted: a client that does not know about one of
     * them must not silently switch it off.
     */
    @Test
    void refusesAMissingTurnComplete() {
        assertInvalid(new PushPreferenceRequest(null, true, true, true));
    }

    @Test
    void refusesAMissingElicitation() {
        assertInvalid(new PushPreferenceRequest(true, null, true, true));
    }

    @SuppressWarnings("all")
    private static PushPreference stored(boolean turnComplete, boolean elicitation, boolean interestOffers, boolean interestUpdates) {
        PushPreference pushPreference = new PushPreference();
        pushPreference.setUserId(USER_ID);
        pushPreference.setTurnComplete(turnComplete);
        pushPreference.setElicitation(elicitation);
        pushPreference.setInterestOffers(interestOffers);
        pushPreference.setInterestUpdates(interestUpdates);
        return pushPreference;
    }

    @Test
    void refusesAMissingBody() {
        assertInvalid(null);
    }

    private void assertInvalid(PushPreferenceRequest pushPreferenceRequest) {
        assertThatThrownBy(() -> pushPreferenceService.update(USER_ID, pushPreferenceRequest))
                .isInstanceOfSatisfying(PushException.class,
                        pushException -> assertThat(pushException.getErrorCode()).isEqualTo(PushErrorCode.INVALID_PREFERENCES));

        verify(pushPreferenceRepository, never()).save(any());
    }
}
