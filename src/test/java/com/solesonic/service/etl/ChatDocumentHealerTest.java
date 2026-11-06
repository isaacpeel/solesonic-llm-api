package com.solesonic.service.etl;

import com.solesonic.exception.llm.LlmKeyMissingException;
import com.solesonic.model.chat.attachment.ChatAttachmentIndexState;
import com.solesonic.model.chat.attachment.ExtractionFailureReason;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import com.solesonic.service.llm.UserLlmKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.solesonic.model.chat.attachment.ExtractionFailureReason.DOCUMENT_TOO_LARGE;
import static com.solesonic.model.chat.attachment.ExtractionFailureReason.DOCUMENT_UNREADABLE;
import static com.solesonic.model.chat.attachment.ExtractionFailureReason.EMBEDDING_UNAVAILABLE;
import static com.solesonic.model.chat.attachment.ExtractionFailureReason.EXCEEDED_DOCUMENT_LIMIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatDocumentHealerTest {

    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");

    @Mock
    private ChatAttachmentService chatAttachmentService;

    @Mock
    private ChatDocumentIngestionService chatDocumentIngestionService;

    @Mock
    private UserLlmKeyService userLlmKeyService;

    private final AtomicReference<Instant> clockInstant = new AtomicReference<>(NOW);

    private UUID chatId;
    private UUID userId;

    @BeforeEach
    void beforeEach() {
        chatId = UUID.randomUUID();
        userId = UUID.randomUUID();

        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(userLlmKeyService).runAsUser(any(), any());
    }

    private ChatDocumentHealer healer(boolean enabled, Executor executor) {
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return clockInstant.get();
            }
        };

        return new ChatDocumentHealer(chatAttachmentService, chatDocumentIngestionService, userLlmKeyService,
                enabled, Duration.ofMinutes(10), 2, Duration.ofHours(1), Duration.ofMinutes(15), executor, clock);
    }

    private ChatDocumentHealer healer() {
        return healer(true, Runnable::run);
    }

    private static ZonedDateTime now() {
        return NOW.atZone(ZoneOffset.UTC);
    }

    private ChatAttachmentIndexState state(Integer chunkCount,
                                           ExtractionFailureReason failureReason,
                                           boolean detached,
                                           ZonedDateTime claimedAt,
                                           ZonedDateTime attemptedAt) {
        return new ChatAttachmentIndexState(UUID.randomUUID(), userId, chatId, "application/pdf", 100,
                chunkCount, failureReason, detached, claimedAt, attemptedAt);
    }

    private void inChat(ChatAttachmentIndexState... states) {
        when(chatAttachmentService.documentIndexStates(chatId)).thenReturn(List.of(states));
    }

    private void health(ChatAttachmentIndexState state, ChatDocumentIngestionService.Health health) {
        when(chatDocumentIngestionService.health(state)).thenReturn(health);
    }

    @Test
    void test_disabled_does_nothing() {
        inChat(state(null, null, false, null, null));

        healer(false, Runnable::run).schedule(chatId, userId, Set.of());

        verifyNoInteractions(chatAttachmentService, chatDocumentIngestionService, userLlmKeyService);
    }

    @Test
    void test_heals_unindexed_and_broken_but_not_healthy_in_flight_or_detached() {
        ChatAttachmentIndexState unindexed = state(null, null, false, null, null);
        ChatAttachmentIndexState broken = state(5, null, false, null, null);
        ChatAttachmentIndexState healthy = state(5, null, false, null, null);
        ChatAttachmentIndexState inFlight = state(5, null, false, null, null);
        ChatAttachmentIndexState detached = state(5, null, true, null, null);

        health(unindexed, ChatDocumentIngestionService.Health.UNINDEXED);
        health(broken, ChatDocumentIngestionService.Health.BROKEN);
        health(healthy, ChatDocumentIngestionService.Health.HEALTHY);
        health(inFlight, ChatDocumentIngestionService.Health.IN_FLIGHT);
        health(detached, ChatDocumentIngestionService.Health.DETACHED);

        inChat(healthy, inFlight, detached, unindexed, broken);

        healer().schedule(chatId, userId, Set.of());

        verify(chatDocumentIngestionService).heal(chatId, userId, unindexed.id());
        verify(chatDocumentIngestionService).heal(chatId, userId, broken.id());
        verify(chatDocumentIngestionService, never()).heal(chatId, userId, healthy.id());
        verify(chatDocumentIngestionService, never()).heal(chatId, userId, inFlight.id());
        verify(chatDocumentIngestionService, never()).heal(chatId, userId, detached.id());
    }

    /**
     * The attachments sent this turn were just handled by the send path; a heal of the same ids
     * would only contend for their lease.
     */
    @Test
    void test_excludes_the_attachments_sent_this_turn() {
        ChatAttachmentIndexState sent = state(null, null, false, null, null);
        health(sent, ChatDocumentIngestionService.Health.UNINDEXED);
        inChat(sent);

        healer().schedule(chatId, userId, Set.of(sent.id()));

        verify(chatDocumentIngestionService, never()).heal(any(), any(), any());
    }

    @Test
    void test_skips_an_attachment_another_worker_is_indexing() {
        ChatAttachmentIndexState leased = state(null, null, false, now().minusMinutes(1), now().minusMinutes(1));
        health(leased, ChatDocumentIngestionService.Health.UNINDEXED);
        inChat(leased);

        healer().schedule(chatId, userId, Set.of());

        verify(chatDocumentIngestionService, never()).heal(any(), any(), any());
    }

    @Test
    void test_never_retries_a_permanent_failure() {
        List<ChatAttachmentIndexState> permanent = new ArrayList<>();

        for (ExtractionFailureReason reason : List.of(DOCUMENT_TOO_LARGE, DOCUMENT_UNREADABLE, EXCEEDED_DOCUMENT_LIMIT)) {
            ChatAttachmentIndexState state = state(null, reason, false, null, null);
            health(state, ChatDocumentIngestionService.Health.UNINDEXED);
            permanent.add(state);
        }

        inChat(permanent.toArray(ChatAttachmentIndexState[]::new));

        healer().schedule(chatId, userId, Set.of());

        verify(chatDocumentIngestionService, never()).heal(any(), any(), any());
    }

    @Test
    void test_retries_a_transient_failure_only_after_the_cooldown() {
        ChatAttachmentIndexState recent = state(null, EMBEDDING_UNAVAILABLE, false, null, now().minusMinutes(5));
        ChatAttachmentIndexState old = state(null, EMBEDDING_UNAVAILABLE, false, null, now().minusHours(2));
        health(recent, ChatDocumentIngestionService.Health.UNINDEXED);
        health(old, ChatDocumentIngestionService.Health.UNINDEXED);
        inChat(recent, old);

        healer().schedule(chatId, userId, Set.of());

        verify(chatDocumentIngestionService, never()).heal(chatId, userId, recent.id());
        verify(chatDocumentIngestionService).heal(chatId, userId, old.id());
    }

    @Test
    void test_heals_at_most_max_per_run() {
        List<ChatAttachmentIndexState> states = new ArrayList<>();

        for (int index = 0; index < 5; index++) {
            ChatAttachmentIndexState state = state(null, null, false, null, null);
            health(state, ChatDocumentIngestionService.Health.UNINDEXED);
            states.add(state);
        }

        inChat(states.toArray(ChatAttachmentIndexState[]::new));

        healer().schedule(chatId, userId, Set.of());

        verify(chatDocumentIngestionService, times(2)).heal(eq(chatId), eq(userId), any());
    }

    @Test
    void test_runs_once_per_chat_per_interval() {
        ChatAttachmentIndexState unindexed = state(null, null, false, null, null);
        health(unindexed, ChatDocumentIngestionService.Health.UNINDEXED);
        inChat(unindexed);

        ChatDocumentHealer healer = healer();

        healer.schedule(chatId, userId, Set.of());
        healer.schedule(chatId, userId, Set.of());

        verify(chatAttachmentService, times(1)).documentIndexStates(chatId);

        clockInstant.set(NOW.plus(Duration.ofMinutes(11)));
        healer.schedule(chatId, userId, Set.of());

        verify(chatAttachmentService, times(2)).documentIndexStates(chatId);
    }

    /**
     * A rejected task never ran, so its throttle entry must not stop the next turn from trying.
     */
    @Test
    void test_a_rejected_run_does_not_hold_the_throttle() {
        inChat();

        AtomicInteger submissions = new AtomicInteger();
        ChatDocumentHealer healer = healer(true, runnable -> {
            if (submissions.getAndIncrement() == 0) {
                throw new RejectedExecutionException("queue full");
            }

            runnable.run();
        });

        assertThatCode(() -> healer.schedule(chatId, userId, Set.of())).doesNotThrowAnyException();
        healer.schedule(chatId, userId, Set.of());

        verify(chatAttachmentService, times(1)).documentIndexStates(chatId);
    }

    /**
     * Security review: the queue is shared, so one user with many chats is capped at two pending runs
     * and cannot starve everyone else. A finished run frees its place.
     */
    @Test
    void test_one_user_has_at_most_two_pending_runs() {
        List<Runnable> queued = new ArrayList<>();
        ChatDocumentHealer healer = healer(true, queued::add);

        for (int chat = 0; chat < 4; chat++) {
            healer.schedule(UUID.randomUUID(), userId, Set.of());
        }

        assertThat(queued).hasSize(2);

        // Another user is not affected.
        healer.schedule(UUID.randomUUID(), UUID.randomUUID(), Set.of());
        assertThat(queued).hasSize(3);

        // One of the first user's runs finishes; the next chat of theirs is accepted.
        when(chatAttachmentService.documentIndexStates(any())).thenReturn(List.of());
        queued.getFirst().run();
        healer.schedule(UUID.randomUUID(), userId, Set.of());
        assertThat(queued).hasSize(4);
    }

    /**
     * A user with no stored key cannot embed anything. That is not a failure to record, and the
     * throttle is released so the next turn (after they add a key) tries again.
     */
    @Test
    void test_no_key_skips_quietly_and_releases_the_throttle() {
        doThrow(new LlmKeyMissingException()).when(userLlmKeyService).runAsUser(any(), any());

        ChatDocumentHealer healer = healer();

        assertThatCode(() -> healer.schedule(chatId, userId, Set.of())).doesNotThrowAnyException();
        assertThatCode(() -> healer.schedule(chatId, userId, Set.of())).doesNotThrowAnyException();

        verify(userLlmKeyService, times(2)).runAsUser(any(), any());
        verifyNoInteractions(chatDocumentIngestionService);
    }

    @Test
    void test_swallows_every_exception_and_keeps_healing_the_rest() {
        ChatAttachmentIndexState first = state(null, null, false, null, null);
        ChatAttachmentIndexState second = state(null, null, false, null, null);
        health(first, ChatDocumentIngestionService.Health.UNINDEXED);
        health(second, ChatDocumentIngestionService.Health.UNINDEXED);
        inChat(first, second);
        when(chatDocumentIngestionService.heal(chatId, userId, first.id())).thenThrow(new RuntimeException("boom"));

        assertThatCode(() -> healer().schedule(chatId, userId, Set.of())).doesNotThrowAnyException();

        verify(chatDocumentIngestionService).heal(chatId, userId, second.id());
    }

    @Test
    void test_a_failing_lookup_never_escapes() {
        when(chatAttachmentService.documentIndexStates(chatId)).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> healer().schedule(chatId, userId, Set.of())).doesNotThrowAnyException();
    }

    /**
     * Only the requesting user's own attachments: a conversation has one owner, but nothing here
     * should be the reason someone else's attachment is embedded on this user's key.
     */
    @Test
    void test_ignores_attachments_of_another_user() {
        ChatAttachmentIndexState foreign = new ChatAttachmentIndexState(UUID.randomUUID(), UUID.randomUUID(), chatId,
                "application/pdf", 100, null, null, false, null, null);
        health(foreign, ChatDocumentIngestionService.Health.UNINDEXED);
        inChat(foreign);

        healer().schedule(chatId, userId, Set.of());

        verify(chatDocumentIngestionService, never()).heal(any(), any(), any());
    }
}
