package com.solesonic.service.interest;

import com.solesonic.config.interest.InterestSettings;
import com.solesonic.exception.interest.InterestException;
import com.solesonic.model.chat.group.ChatGroup;
import com.solesonic.model.interest.AgentCadence;
import com.solesonic.model.interest.AgentStatus;
import com.solesonic.model.interest.AgentType;
import com.solesonic.model.interest.InterestAcceptRequest;
import com.solesonic.model.interest.InterestAgent;
import com.solesonic.model.interest.InterestAgentSummary;
import com.solesonic.model.interest.InterestErrorCode;
import com.solesonic.model.interest.InterestOffer;
import com.solesonic.model.interest.OfferStatus;
import com.solesonic.repository.chat.ChatGroupRepository;
import com.solesonic.repository.interest.InterestAgentRepository;
import com.solesonic.repository.interest.InterestOfferRepository;
import com.solesonic.repository.memory.UserMemoryRepository;
import com.solesonic.service.security.ResourceOwnershipService;
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
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InterestOfferServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private static final ZonedDateTime NOW_ZONED = NOW.atZone(ZoneOffset.UTC);
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();
    private static final UUID MEMORY_ID = UUID.randomUUID();

    @Mock
    private InterestOfferRepository interestOfferRepository;

    @Mock
    private InterestAgentRepository interestAgentRepository;

    @Mock
    private UserMemoryRepository userMemoryRepository;

    @Mock
    private ChatGroupRepository chatGroupRepository;

    @Mock
    private ResourceOwnershipService resourceOwnershipService;

    private InterestOfferService interestOfferService;

    @BeforeEach
    void setUp() {
        interestOfferService = new InterestOfferService(interestOfferRepository,
                interestAgentRepository,
                userMemoryRepository,
                chatGroupRepository,
                resourceOwnershipService,
                InterestSettingsFixture.settings(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        when(resourceOwnershipService.hasAuthority(InterestOfferService.WEB_SEARCH_AUTHORITY)).thenReturn(true);
        when(userMemoryRepository.countByUserIdAndIdInAndStatusIn(eq(USER_ID), anyCollection(), anyCollection())).thenReturn(1L);
        when(interestAgentRepository.countByUserId(USER_ID)).thenReturn(0L);
        when(chatGroupRepository.save(any(ChatGroup.class))).thenAnswer(invocation -> {
            ChatGroup chatGroup = invocation.getArgument(0);
            chatGroup.setId(UUID.randomUUID());
            return chatGroup;
        });
        when(interestAgentRepository.save(any(InterestAgent.class))).thenAnswer(invocation -> {
            InterestAgent interestAgent = invocation.getArgument(0);
            interestAgent.setId(UUID.randomUUID());
            return interestAgent;
        });
        when(interestOfferRepository.save(any(InterestOffer.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void acceptCreatesOneActiveAgentDueNowAndOneChatGroupAndMarksTheOfferAccepted() {
        InterestOffer offer = pendingOffer(USER_ID);
        when(interestOfferRepository.lockByIdAndUserId(offer.getId(), USER_ID)).thenReturn(Optional.of(offer));

        InterestAgentSummary summary = interestOfferService.accept(USER_ID, offer.getId(), new InterestAcceptRequest(AgentCadence.WEEKLY));

        ArgumentCaptor<ChatGroup> chatGroup = ArgumentCaptor.forClass(ChatGroup.class);
        verify(chatGroupRepository).save(chatGroup.capture());
        assertThat(chatGroup.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(chatGroup.getValue().getName()).isEqualTo("News: Formula 1");

        ArgumentCaptor<InterestAgent> agent = ArgumentCaptor.forClass(InterestAgent.class);
        verify(interestAgentRepository).save(agent.capture());
        assertThat(agent.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(agent.getValue().getOfferId()).isEqualTo(offer.getId());
        assertThat(agent.getValue().getTopicKey()).isEqualTo("sports.formula-1");
        assertThat(agent.getValue().getQuery()).isEqualTo("Formula 1 race results");
        assertThat(agent.getValue().getStatus()).isEqualTo(AgentStatus.ACTIVE);
        assertThat(agent.getValue().getCadence()).isEqualTo(AgentCadence.WEEKLY);
        assertThat(agent.getValue().getNextRunAt()).isEqualTo(NOW_ZONED);
        assertThat(agent.getValue().getChatGroupId()).isEqualTo(chatGroup.getValue().getId());

        assertThat(offer.getStatus()).isEqualTo(OfferStatus.ACCEPTED);
        assertThat(offer.getDecided()).isEqualTo(NOW_ZONED);
        assertThat(summary.label()).isEqualTo("Formula 1");
    }

    @Test
    void acceptDefaultsToDailyWithoutABody() {
        InterestOffer offer = pendingOffer(USER_ID);
        when(interestOfferRepository.lockByIdAndUserId(offer.getId(), USER_ID)).thenReturn(Optional.of(offer));

        InterestAgentSummary summary = interestOfferService.accept(USER_ID, offer.getId(), null);

        assertThat(summary.cadence()).isEqualTo(AgentCadence.DAILY);
    }

    @Test
    void acceptingAnotherUsersOfferIsNotFoundAndCreatesNothing() {
        InterestOffer offer = pendingOffer(OTHER_USER_ID);
        when(interestOfferRepository.lockByIdAndUserId(offer.getId(), USER_ID)).thenReturn(Optional.empty());

        assertError(() -> interestOfferService.accept(USER_ID, offer.getId(), null), InterestErrorCode.OFFER_NOT_FOUND);

        assertNothingCreated();
    }

    @Test
    void acceptingADecidedOfferIsAConflict() {
        for (OfferStatus decided : List.of(OfferStatus.ACCEPTED, OfferStatus.DECLINED, OfferStatus.EXPIRED, OfferStatus.WITHDRAWN)) {
            InterestOffer offer = pendingOffer(USER_ID);
            offer.setStatus(decided);
            when(interestOfferRepository.lockByIdAndUserId(offer.getId(), USER_ID)).thenReturn(Optional.of(offer));

            assertError(() -> interestOfferService.accept(USER_ID, offer.getId(), null), InterestErrorCode.OFFER_ALREADY_DECIDED);
        }

        assertNothingCreated();
    }

    @Test
    void acceptingAnExpiredOfferIsAConflictAndExpiresIt() {
        InterestOffer offer = pendingOffer(USER_ID);
        offer.setExpires(NOW_ZONED.minusSeconds(1));
        when(interestOfferRepository.lockByIdAndUserId(offer.getId(), USER_ID)).thenReturn(Optional.of(offer));

        assertError(() -> interestOfferService.accept(USER_ID, offer.getId(), null), InterestErrorCode.OFFER_EXPIRED);

        assertThat(offer.getStatus()).isEqualTo(OfferStatus.EXPIRED);
        assertNothingCreated();
    }

    @Test
    void acceptWithoutTheWebSearchRoleIsForbidden() {
        InterestOffer offer = pendingOffer(USER_ID);
        when(interestOfferRepository.lockByIdAndUserId(offer.getId(), USER_ID)).thenReturn(Optional.of(offer));
        when(resourceOwnershipService.hasAuthority(InterestOfferService.WEB_SEARCH_AUTHORITY)).thenReturn(false);

        assertError(() -> interestOfferService.accept(USER_ID, offer.getId(), null), InterestErrorCode.NOT_PERMITTED);

        assertThat(offer.getStatus()).isEqualTo(OfferStatus.PENDING);
        assertNothingCreated();
    }

    @Test
    void acceptAtTheAgentLimitIsAConflict() {
        InterestOffer offer = pendingOffer(USER_ID);
        when(interestOfferRepository.lockByIdAndUserId(offer.getId(), USER_ID)).thenReturn(Optional.of(offer));
        when(interestAgentRepository.countByUserId(USER_ID)).thenReturn((long) InterestSettingsFixture.MAX_AGENTS_PER_USER);

        assertError(() -> interestOfferService.accept(USER_ID, offer.getId(), null), InterestErrorCode.AGENT_LIMIT_REACHED);

        assertNothingCreated();
    }

    /**
     * Deleting the conversation that taught the only source memory withdraws the question it
     * prompted; accept re-checks in case the withdrawal has not happened yet.
     */
    @Test
    void acceptAfterTheSourceMemoriesWereForgottenWithdrawsTheOffer() {
        InterestOffer offer = pendingOffer(USER_ID);
        when(interestOfferRepository.lockByIdAndUserId(offer.getId(), USER_ID)).thenReturn(Optional.of(offer));
        when(userMemoryRepository.countByUserIdAndIdInAndStatusIn(eq(USER_ID), anyCollection(), anyCollection())).thenReturn(0L);

        assertError(() -> interestOfferService.accept(USER_ID, offer.getId(), null), InterestErrorCode.OFFER_WITHDRAWN);

        assertThat(offer.getStatus()).isEqualTo(OfferStatus.WITHDRAWN);
        assertThat(offer.getDecided()).isEqualTo(NOW_ZONED);
        assertNothingCreated();
    }

    @Test
    void acceptForATopicTheUserAlreadyWatchesWithdrawsTheOffer() {
        InterestOffer offer = pendingOffer(USER_ID);
        when(interestOfferRepository.lockByIdAndUserId(offer.getId(), USER_ID)).thenReturn(Optional.of(offer));
        when(interestAgentRepository.existsByUserIdAndTopicKey(USER_ID, "sports.formula-1")).thenReturn(true);

        assertError(() -> interestOfferService.accept(USER_ID, offer.getId(), null), InterestErrorCode.OFFER_WITHDRAWN);

        assertNothingCreated();
    }

    @Test
    void declineMarksTheOfferDeclined() {
        InterestOffer offer = pendingOffer(USER_ID);
        when(interestOfferRepository.lockByIdAndUserId(offer.getId(), USER_ID)).thenReturn(Optional.of(offer));

        interestOfferService.decline(USER_ID, offer.getId());

        assertThat(offer.getStatus()).isEqualTo(OfferStatus.DECLINED);
        assertThat(offer.getDecided()).isEqualTo(NOW_ZONED);
        verify(interestOfferRepository).save(offer);
    }

    @Test
    void decliningAnotherUsersOfferIsNotFound() {
        UUID offerId = UUID.randomUUID();
        when(interestOfferRepository.lockByIdAndUserId(offerId, USER_ID)).thenReturn(Optional.empty());

        assertError(() -> interestOfferService.decline(USER_ID, offerId), InterestErrorCode.OFFER_NOT_FOUND);

        verify(interestOfferRepository, never()).save(any());
    }

    @Test
    void decliningADecidedOfferIsAConflict() {
        InterestOffer offer = pendingOffer(USER_ID);
        offer.setStatus(OfferStatus.ACCEPTED);
        when(interestOfferRepository.lockByIdAndUserId(offer.getId(), USER_ID)).thenReturn(Optional.of(offer));

        assertError(() -> interestOfferService.decline(USER_ID, offer.getId()), InterestErrorCode.OFFER_ALREADY_DECIDED);
    }

    @Test
    void pendingListsOnlyTheCallersUnexpiredPendingOffers() {
        InterestOffer offer = pendingOffer(USER_ID);
        when(interestOfferRepository.findByUserIdAndStatusAndExpiresAfterOrderByCreatedDesc(USER_ID, OfferStatus.PENDING, NOW_ZONED))
                .thenReturn(List.of(offer));

        assertThat(interestOfferService.pending(USER_ID)).singleElement()
                .satisfies(summary -> assertThat(summary.id()).isEqualTo(offer.getId()));

        verify(interestOfferRepository, never()).findByUserIdAndStatusAndExpiresAfterOrderByCreatedDesc(eq(OTHER_USER_ID), any(), any());
    }

    @Test
    void gettingAnotherUsersOfferIsNotFound() {
        UUID offerId = UUID.randomUUID();
        when(interestOfferRepository.findByIdAndUserId(offerId, USER_ID)).thenReturn(Optional.empty());

        assertError(() -> interestOfferService.get(USER_ID, offerId), InterestErrorCode.OFFER_NOT_FOUND);
    }

    private void assertNothingCreated() {
        verify(chatGroupRepository, never()).save(any());
        verify(interestAgentRepository, never()).save(any());
    }

    static void assertError(Runnable call, InterestErrorCode errorCode) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(InterestException.class,
                        interestException -> assertThat(interestException.getErrorCode()).isEqualTo(errorCode));
    }

    static InterestOffer pendingOffer(UUID userId) {
        InterestOffer offer = new InterestOffer();
        offer.setId(UUID.randomUUID());
        offer.setUserId(userId);
        offer.setAgentType(AgentType.NEWS);
        offer.setTopicKey("sports.formula-1");
        offer.setLabel("Formula 1");
        offer.setQuery("Formula 1 race results");
        offer.setRationale("You follow Formula 1");
        offer.setSourceMemoryIds(List.of(MEMORY_ID));
        offer.setConfidence(0.9);
        offer.setStatus(OfferStatus.PENDING);
        offer.setCreated(NOW_ZONED.minusDays(1));
        offer.setExpires(NOW_ZONED.plusDays(6));
        return offer;
    }
}
