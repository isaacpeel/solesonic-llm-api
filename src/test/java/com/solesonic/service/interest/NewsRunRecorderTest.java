package com.solesonic.service.interest;

import com.solesonic.model.chat.group.ChatGroup;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.model.interest.AgentCadence;
import com.solesonic.model.interest.AgentStatus;
import com.solesonic.model.interest.AgentType;
import com.solesonic.model.interest.InterestAgent;
import com.solesonic.model.interest.InterestAgentItem;
import com.solesonic.model.interest.InterestUpdate;
import com.solesonic.repository.chat.ChatGroupRepository;
import com.solesonic.repository.interest.InterestAgentItemRepository;
import com.solesonic.repository.interest.InterestAgentRepository;
import com.solesonic.repository.interest.InterestUpdateRepository;
import com.solesonic.service.user.UserPreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NewsRunRecorderTest {
    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-10-21T15:00:00Z");
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CHAT_GROUP_ID = UUID.randomUUID();
    private static final UUID CHAT_ID = UUID.randomUUID();
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @Mock
    private InterestAgentRepository interestAgentRepository;

    @Mock
    private InterestAgentItemRepository interestAgentItemRepository;

    @Mock
    private InterestUpdateRepository interestUpdateRepository;

    @Mock
    private ChatGroupRepository chatGroupRepository;

    @Mock
    private InterestChatWriter interestChatWriter;

    @Mock
    private UserPreferencesService userPreferencesService;

    @Captor
    private ArgumentCaptor<List<InterestAgentItem>> itemsCaptor;

    private NewsRunRecorder newsRunRecorder;
    private InterestAgent agent;

    @BeforeEach
    void setUp() {
        newsRunRecorder = new NewsRunRecorder(interestAgentRepository, interestAgentItemRepository, interestUpdateRepository,
                chatGroupRepository, interestChatWriter, userPreferencesService, InterestSettingsFixture.settings());

        agent = agent();

        when(interestAgentRepository.lockByIdAndUserId(agent.getId(), USER_ID)).thenReturn(Optional.of(agent));
        when(interestAgentRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(interestAgentRepository.save(any(InterestAgent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(interestAgentItemRepository.findSeen(any(), any(), anyCollection())).thenReturn(List.of());
        when(chatGroupRepository.findByIdAndUserId(CHAT_GROUP_ID, USER_ID)).thenReturn(Optional.of(new ChatGroup()));
        when(userPreferencesService.getZone(USER_ID)).thenReturn(NEW_YORK);

        Chat chat = new Chat();
        chat.setId(CHAT_ID);
        when(interestChatWriter.write(eq(USER_ID), any(), any(), any(), any())).thenReturn(chat);
    }

    /**
     * The lease is what makes a claim whose instance died get retried rather than lost.
     */
    @Test
    void claimMovesEachDueAgentForwardByTheLease() {
        when(interestAgentRepository.findDue(eq(NOW), anyInt())).thenReturn(List.of(agent));

        newsRunRecorder.claim(NOW);

        assertThat(agent.getNextRunAt()).isEqualTo(NOW.plus(InterestSettingsFixture.RUN_LEASE));
    }

    @Test
    void deliveringWritesTheChatForTheAgentsOwnerInItsGroupWithAnUpdateRowAndTheSeenHashes() {
        UUID updateId = UUID.randomUUID();

        Optional<UUID> chatId = newsRunRecorder.deliver(agent, List.of("a", "b"), updateId, "Formula 1 · Oct 21", "text", 2, NOW);

        assertThat(chatId).contains(CHAT_ID);
        verify(interestChatWriter).write(USER_ID, "Formula 1 · Oct 21", CHAT_GROUP_ID, "text", NOW);

        ArgumentCaptor<InterestUpdate> update = ArgumentCaptor.forClass(InterestUpdate.class);
        verify(interestUpdateRepository).save(update.capture());
        assertThat(update.getValue().getId()).isEqualTo(updateId);
        assertThat(update.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(update.getValue().getAgentId()).isEqualTo(agent.getId());
        assertThat(update.getValue().getChatId()).isEqualTo(CHAT_ID);
        assertThat(update.getValue().getItemCount()).isEqualTo(2);

        assertThat(savedItems()).extracting(InterestAgentItem::getUrlHash).containsExactly("a", "b");

        assertThat(agent.getLastUpdateAt()).isEqualTo(NOW);
        assertThat(agent.getFailures()).isZero();
        assertThat(agent.getNextRunAt().withZoneSameInstant(NEW_YORK).toLocalDateTime()).hasToString("2026-10-22T08:00");
    }

    @Test
    void anAgentDeletedMidRunWritesNothing() {
        when(interestAgentRepository.lockByIdAndUserId(agent.getId(), USER_ID)).thenReturn(Optional.empty());

        assertThat(newsRunRecorder.deliver(agent, List.of("a"), UUID.randomUUID(), "n", "t", 1, NOW)).isEmpty();

        verify(interestChatWriter, never()).write(any(), any(), any(), any(), any());
        verify(interestUpdateRepository, never()).save(any());
        verify(interestAgentItemRepository, never()).saveAll(anyList());
    }

    @Test
    void startingARunRenewsItsLease() {
        ZonedDateTime later = NOW.plusMinutes(20);

        Optional<InterestAgent> started = newsRunRecorder.start(claimedCopy(), later);

        assertThat(started).isPresent();
        assertThat(agent.getNextRunAt()).isEqualTo(later.plus(InterestSettingsFixture.RUN_LEASE));
    }

    /**
     * Another instance re-claimed the agent after this run's lease lapsed: its lease moved, so this run
     * neither starts nor, if it had started, writes anything.
     */
    @Test
    void aRunWhoseLeaseMovedNeitherStartsNorDelivers() {
        InterestAgent staleClaim = claimedCopy();
        agent.setNextRunAt(NOW.plusMinutes(45));

        assertThat(newsRunRecorder.start(staleClaim, NOW)).isEmpty();
        assertThat(newsRunRecorder.deliver(staleClaim, List.of("a"), UUID.randomUUID(), "n", "t", 1, NOW)).isEmpty();

        verify(interestChatWriter, never()).write(any(), any(), any(), any(), any());
    }

    @Test
    void aFailedRunDoesNotCountAsTheLastSuccessfulRun() {
        newsRunRecorder.failed(agent, NOW);

        assertThat(agent.getLastRunAt()).isNull();
    }

    @Test
    void anAgentPausedMidRunWritesNothing() {
        agent.setStatus(AgentStatus.PAUSED);

        assertThat(newsRunRecorder.deliver(agent, List.of("a"), UUID.randomUUID(), "n", "t", 1, NOW)).isEmpty();

        verify(interestChatWriter, never()).write(any(), any(), any(), any(), any());
    }

    /**
     * The user deleted the agent's group: the chat is filed ungrouped and the agent forgets the group.
     */
    @Test
    void aDeletedChatGroupFilesTheChatUngroupedAndClearsTheAgentsGroup() {
        when(chatGroupRepository.findByIdAndUserId(CHAT_GROUP_ID, USER_ID)).thenReturn(Optional.empty());

        newsRunRecorder.deliver(agent, List.of("a"), UUID.randomUUID(), "n", "t", 1, NOW);

        verify(interestChatWriter).write(USER_ID, "n", null, "t", NOW);
        assertThat(agent.getChatGroupId()).isNull();
    }

    @Test
    void aQuietRunRecordsTheHashesAndSchedulesTheNextRunWithoutAChat() {
        newsRunRecorder.quiet(agent, List.of("a", "a", "b"), NOW);

        assertThat(savedItems()).extracting(InterestAgentItem::getUrlHash).containsExactly("a", "b");
        assertThat(agent.getLastRunAt()).isEqualTo(NOW);
        verify(interestChatWriter, never()).write(any(), any(), any(), any(), any());
    }

    @Test
    void failuresBackOffAndPauseTheAgentAtTheLimit() {
        newsRunRecorder.failed(agent, NOW);

        assertThat(agent.getFailures()).isEqualTo(1);
        assertThat(agent.getNextRunAt()).isEqualTo(NOW.plusHours(2));
        assertThat(agent.getLastFailureAt()).isEqualTo(NOW);
        assertThat(agent.getStatus()).isEqualTo(AgentStatus.ACTIVE);

        agent.setFailures(InterestSettingsFixture.MAX_FAILURES - 1);
        newsRunRecorder.failed(agent, NOW);

        assertThat(agent.getStatus()).isEqualTo(AgentStatus.PAUSED);
        verify(interestChatWriter, never()).write(any(), any(), any(), any(), any());
    }

    @Test
    void prunesOnlyItemsOlderThanTheRetention() {
        newsRunRecorder.pruneItems(NOW);

        verify(interestAgentItemRepository).deleteByFirstSeenBefore(NOW.minusDays(30));
    }

    private List<InterestAgentItem> savedItems() {
        verify(interestAgentItemRepository).saveAll(itemsCaptor.capture());
        return itemsCaptor.getValue();
    }

    /**
     * The claim as a run holds it: a separate instance from the locked row, as it is after the claim's
     * transaction has ended.
     */
    private InterestAgent claimedCopy() {
        InterestAgent claimed = new InterestAgent();
        claimed.setId(agent.getId());
        claimed.setUserId(USER_ID);
        claimed.setNextRunAt(agent.getNextRunAt());
        return claimed;
    }

    private static InterestAgent agent() {
        InterestAgent agent = new InterestAgent();
        agent.setId(UUID.randomUUID());
        agent.setUserId(USER_ID);
        agent.setAgentType(AgentType.NEWS);
        agent.setTopicKey("sports.formula-1");
        agent.setLabel("Formula 1");
        agent.setQuery("Formula 1 race results");
        agent.setChatGroupId(CHAT_GROUP_ID);
        agent.setCadence(AgentCadence.DAILY);
        agent.setStatus(AgentStatus.ACTIVE);
        agent.setNextRunAt(NOW);
        return agent;
    }
}
