package com.solesonic.service.interest;

import com.solesonic.model.interest.InterestOffer;
import com.solesonic.model.interest.InterestScan;
import com.solesonic.model.interest.OfferStatus;
import com.solesonic.model.interest.ScanStatus;
import com.solesonic.repository.interest.InterestAgentRepository;
import com.solesonic.repository.interest.InterestOfferRepository;
import com.solesonic.repository.interest.InterestScanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InterestScanServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private static final ZonedDateTime NOW_ZONED = NOW.atZone(ZoneOffset.UTC);
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID MEMORY_ID = UUID.randomUUID();
    private static final ValidCandidate CANDIDATE = new ValidCandidate("sports.formula-1", "Formula 1", "Formula 1 race results",
            "You follow Formula 1", 0.9, List.of(MEMORY_ID));

    @Mock
    private InterestScanRepository interestScanRepository;

    @Mock
    private InterestOfferRepository interestOfferRepository;

    @Mock
    private InterestAgentRepository interestAgentRepository;

    private InterestScanService interestScanService;
    private InterestScan interestScan;

    @BeforeEach
    void setUp() {
        interestScanService = new InterestScanService(interestScanRepository, interestOfferRepository, interestAgentRepository,
                InterestSettingsFixture.settings(), Clock.fixed(NOW, ZoneOffset.UTC));

        interestScan = new InterestScan();
        interestScan.setUserId(USER_ID);
        interestScan.setStatus(ScanStatus.IN_PROGRESS);
        interestScan.setAttempts(1);

        when(interestScanRepository.lockByUserId(USER_ID)).thenReturn(Optional.of(interestScan));
        when(interestOfferRepository.findBlockedTopicKeys(any(), any())).thenReturn(List.of());
        when(interestOfferRepository.save(any(InterestOffer.class))).thenAnswer(invocation -> {
            InterestOffer offer = invocation.getArgument(0);
            offer.setId(UUID.randomUUID());
            return offer;
        });
        when(interestScanRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void claimMarksEachClaimedRowInProgress() {
        InterestScan pending = new InterestScan();
        pending.setStatus(ScanStatus.PENDING);
        when(interestScanRepository.findClaimable(any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of(pending));

        List<InterestScan> claimed = interestScanService.claim();

        verify(interestScanRepository).seedMissing();
        verify(interestScanRepository).findClaimable(NOW_ZONED.minus(InterestScanService.STALE_CLAIM),
                NOW_ZONED.minus(InterestScanService.RETRY_DELAY),
                NOW_ZONED.minus(Duration.ofDays(7)),
                InterestSettingsFixture.MAX_AGENTS_PER_USER,
                5);
        assertThat(claimed).singleElement().satisfies(scan -> assertThat(scan.getStatus()).isEqualTo(ScanStatus.IN_PROGRESS));
    }

    @Test
    void completeWritesAPendingOfferAndAdvancesTheCursor() {
        ZonedDateTime scannedThrough = NOW_ZONED.minusHours(2);

        InterestScanService.Outcome outcome = interestScanService.complete(USER_ID, scannedThrough, CANDIDATE);

        ArgumentCaptor<InterestOffer> offer = ArgumentCaptor.forClass(InterestOffer.class);
        verify(interestOfferRepository).save(offer.capture());
        assertThat(offer.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(offer.getValue().getStatus()).isEqualTo(OfferStatus.PENDING);
        assertThat(offer.getValue().getSourceMemoryIds()).containsExactly(MEMORY_ID);
        assertThat(offer.getValue().getExpires()).isEqualTo(NOW_ZONED.plusDays(7));
        assertThat(outcome.offerId()).isEqualTo(offer.getValue().getId());

        assertThat(interestScan.getStatus()).isEqualTo(ScanStatus.COMPLETED);
        assertThat(interestScan.getScannedThrough()).isEqualTo(scannedThrough);
        assertThat(interestScan.getLastOffered()).isEqualTo(NOW_ZONED);
        assertThat(interestScan.getAttempts()).isZero();
    }

    @Test
    void aResultForARowNoLongerInProgressIsDiscarded() {
        interestScan.setStatus(ScanStatus.COMPLETED);

        InterestScanService.Outcome outcome = interestScanService.complete(USER_ID, NOW_ZONED, CANDIDATE);

        assertThat(outcome.applied()).isFalse();
        verify(interestOfferRepository, never()).save(any());
    }

    /**
     * Between the claim and the apply the user may have been asked something else or gained an agent
     * for the topic: the candidate is dropped, but the cursor still moves on.
     */
    @Test
    void aCandidateNoLongerOfferableAtApplyIsDroppedButTheCursorAdvances() {
        when(interestOfferRepository.existsByUserIdAndStatus(USER_ID, OfferStatus.PENDING)).thenReturn(true);

        InterestScanService.Outcome outcome = interestScanService.complete(USER_ID, NOW_ZONED, CANDIDATE);

        assertThat(outcome.applied()).isTrue();
        assertThat(outcome.offerId()).isNull();
        assertThat(interestScan.getStatus()).isEqualTo(ScanStatus.COMPLETED);
        assertThat(interestScan.getLastOffered()).isNull();
        verify(interestOfferRepository, never()).save(any());
    }

    @Test
    void aTopicDeclinedSinceTheClaimIsNotOffered() {
        when(interestOfferRepository.findBlockedTopicKeys(any(), any())).thenReturn(List.of("sports.formula-1"));

        assertThat(interestScanService.complete(USER_ID, NOW_ZONED, CANDIDATE).offerId()).isNull();
    }

    @Test
    void failuresCountUntilTheScanFails() {
        interestScanService.recordFailure(USER_ID);
        assertThat(interestScan.getStatus()).isEqualTo(ScanStatus.PENDING);

        interestScanService.recordFailure(USER_ID);
        assertThat(interestScan.getStatus()).isEqualTo(ScanStatus.FAILED);
        assertThat(interestScan.getAttempts()).isEqualTo(3);
    }
}
