package com.solesonic.service.memory;

import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.model.memory.ChatMemoryReflection;
import com.solesonic.model.memory.MemoryKind;
import com.solesonic.model.memory.MemoryOrigin;
import com.solesonic.model.memory.MemoryStatus;
import com.solesonic.model.memory.ReflectionStatus;
import com.solesonic.model.memory.UserMemory;
import com.solesonic.model.memory.UserMemoryEvidence;
import com.solesonic.repository.chat.ChatMessageRepository;
import com.solesonic.repository.chat.ChatRepository;
import com.solesonic.repository.memory.ChatMemoryReflectionRepository;
import com.solesonic.repository.memory.UserMemoryEvidenceRepository;
import com.solesonic.repository.memory.UserMemoryRepository;
import com.solesonic.service.memory.ValidatedReflection.Addition;
import com.solesonic.service.memory.ValidatedReflection.Confirmation;
import com.solesonic.service.memory.ValidatedReflection.Supersession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.MessageType;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserMemoryReflectionApplyTest {
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CHAT_ID = UUID.randomUUID();
    private static final ZonedDateTime NOW = ZonedDateTime.now();
    private static final ZonedDateTime CLAIMED = NOW.minusHours(1);

    @Mock
    private UserMemoryRepository userMemoryRepository;

    @Mock
    private UserMemoryEvidenceRepository userMemoryEvidenceRepository;

    @Mock
    private ChatMemoryReflectionRepository chatMemoryReflectionRepository;

    @Mock
    private ChatRepository chatRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    private final Set<UUID> deletedMessageIds = new HashSet<>();

    @Captor
    private ArgumentCaptor<Iterable<UserMemoryEvidence>> evidenceCaptor;

    private UserMemoryService userMemoryService;

    private final ChatMemoryReflection reflectionRow = new ChatMemoryReflection();
    private final ChatMessage userMessage = message(NOW.minusMinutes(5));

    @BeforeEach
    void setUp() {
        userMemoryService = UserMemoryServiceFixture.service(userMemoryRepository,
                userMemoryEvidenceRepository,
                chatMemoryReflectionRepository,
                chatRepository,
                chatMessageRepository,
                true);

        reflectionRow.setChatId(CHAT_ID);
        reflectionRow.setUserId(USER_ID);
        reflectionRow.setStatus(ReflectionStatus.IN_PROGRESS);
        reflectionRow.setAttempts(2);
        reflectionRow.setReflectedThrough(CLAIMED);

        lenient().when(chatMessageRepository.findAllById(anyIterable())).thenAnswer(invocation -> {
            Iterable<UUID> ids = invocation.getArgument(0);
            List<ChatMessage> existing = new ArrayList<>();

            for (UUID id : ids) {
                if (!deletedMessageIds.contains(id)) {
                    ChatMessage chatMessage = new ChatMessage();
                    chatMessage.setId(id);
                    existing.add(chatMessage);
                }
            }

            return existing;
        });

        lenient().when(chatMemoryReflectionRepository.findByChatIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.of(reflectionRow));
        lenient().when(userMemoryRepository.save(any(UserMemory.class))).thenAnswer(invocation -> {
            UserMemory memory = invocation.getArgument(0);

            if (memory.getId() == null) {
                memory.setId(UUID.randomUUID());
            }

            return memory;
        });
    }

    /**
     * A chat forgotten while the model was being asked must not have its result written afterwards.
     */
    @Test
    void discardsTheResultWhenTheChatWasForgottenMidReflection() {
        when(chatMemoryReflectionRepository.findByChatIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.empty());

        boolean applied = userMemoryService.applyReflection(CHAT_ID, USER_ID, CLAIMED, NOW,
                reflection(List.of(addition(MemoryStatus.ACTIVE)), List.of(), List.of()));

        assertThat(applied).isFalse();
        verify(userMemoryRepository, never()).save(any(UserMemory.class));
        verify(userMemoryEvidenceRepository, never()).saveAll(anyIterable());
    }

    /**
     * Another instance re-claimed the chat after a slow model call and already moved the cursor:
     * applying this result as well would learn the same window twice.
     */
    @Test
    void discardsTheResultWhenTheClaimMovedMeanwhile() {
        reflectionRow.setReflectedThrough(NOW.minusMinutes(1));

        boolean applied = userMemoryService.applyReflection(CHAT_ID, USER_ID, CLAIMED, NOW,
                reflection(List.of(addition(MemoryStatus.ACTIVE)), List.of(), List.of()));

        assertThat(applied).isFalse();
        verify(userMemoryRepository, never()).save(any(UserMemory.class));
        verify(chatMemoryReflectionRepository, never()).save(any(ChatMemoryReflection.class));
    }

    /**
     * A message deleted while the model was being asked was forgotten; nothing may cite it afterwards.
     */
    @Test
    void dropsAnOperationWhoseOnlyEvidenceWasDeletedMeanwhile() {
        deletedMessageIds.add(userMessage.getId());

        boolean applied = userMemoryService.applyReflection(CHAT_ID, USER_ID, CLAIMED, NOW,
                reflection(List.of(addition(MemoryStatus.ACTIVE)), List.of(), List.of()));

        assertThat(applied).isTrue();
        verify(userMemoryRepository, never()).save(any(UserMemory.class));
        verify(userMemoryEvidenceRepository, never()).saveAll(anyIterable());
    }

    @Test
    void advancesTheCursorAndCompletesTheRow() {
        boolean applied = userMemoryService.applyReflection(CHAT_ID, USER_ID, CLAIMED, NOW, ValidatedReflection.empty());

        assertThat(applied).isTrue();
        assertThat(reflectionRow.getReflectedThrough()).isEqualTo(NOW);
        assertThat(reflectionRow.getStatus()).isEqualTo(ReflectionStatus.COMPLETED);
        assertThat(reflectionRow.getAttempts()).isZero();
        verify(chatMemoryReflectionRepository).save(reflectionRow);
    }

    @Test
    void anAdditionIsWrittenForTheOwnerWithItsEvidence() {
        when(userMemoryRepository.findByUserIdAndStatusInOrderByCreatedAsc(eq(USER_ID), anyCollection())).thenReturn(List.of());

        userMemoryService.applyReflection(CHAT_ID, USER_ID, CLAIMED, NOW, reflection(List.of(addition(MemoryStatus.ACTIVE)), List.of(), List.of()));

        verify(userMemoryEvidenceRepository).saveAll(evidenceCaptor.capture());
        assertThat(evidenceCaptor.getValue()).singleElement()
                .satisfies(evidence -> {
                    assertThat(evidence.getUserId()).isEqualTo(USER_ID);
                    assertThat(evidence.getChatId()).isEqualTo(CHAT_ID);
                    assertThat(evidence.getChatMessageId()).isEqualTo(userMessage.getId());
                    assertThat(evidence.getChatMessageTimestamp()).isEqualTo(userMessage.getTimestamp());
                });
    }

    /**
     * The database will not hold two active values for one key even for the length of a flush, so the
     * old row has to be written as superseded before the replacement takes over its key.
     */
    @Test
    void aSupersessionRetiresTheOldRowBeforeTheReplacementTakesItsKey() {
        UserMemory target = memory(MemoryOrigin.INFERRED, MemoryStatus.ACTIVE, "units", 0.8, NOW);

        when(userMemoryRepository.findByIdAndUserIdAndStatusIn(eq(target.getId()), eq(USER_ID), anyCollection()))
                .thenReturn(Optional.of(target));

        userMemoryService.applyReflection(CHAT_ID, USER_ID, CLAIMED, NOW, reflection(List.of(),
                List.of(new Supersession(target, "Prefers imperial", 0.9, MemoryStatus.ACTIVE, List.of(userMessage))),
                List.of()));

        ArgumentCaptor<UserMemory> replacement = ArgumentCaptor.forClass(UserMemory.class);
        InOrder order = inOrder(userMemoryRepository);
        order.verify(userMemoryRepository).saveAndFlush(target);
        order.verify(userMemoryRepository).save(replacement.capture());

        assertThat(target.getStatus()).isEqualTo(MemoryStatus.SUPERSEDED);
        assertThat(target.getContent()).isEqualTo("content");
        assertThat(replacement.getValue().getSupersedes()).isEqualTo(target.getId());
        assertThat(replacement.getValue().getMemoryKey()).isEqualTo("units");
        assertThat(replacement.getValue().getContent()).isEqualTo("Prefers imperial");
        assertThat(replacement.getValue().getOrigin()).isEqualTo(MemoryOrigin.INFERRED);
        assertThat(replacement.getValue().getUserId()).isEqualTo(USER_ID);
    }

    /**
     * The user may have edited the memory while the model was being asked; it is theirs now, and
     * reflection leaves it alone.
     */
    @Test
    void aSupersessionOfAMemoryTheUserEditedMeanwhileIsSkipped() {
        UserMemory stale = memory(MemoryOrigin.INFERRED, MemoryStatus.ACTIVE, "units", 0.8, NOW);
        UserMemory edited = memory(MemoryOrigin.AUTHORED, MemoryStatus.ACTIVE, "units", 1.0, NOW);
        edited.setId(stale.getId());

        when(userMemoryRepository.findByIdAndUserIdAndStatusIn(eq(stale.getId()), eq(USER_ID), anyCollection()))
                .thenReturn(Optional.of(edited));

        userMemoryService.applyReflection(CHAT_ID, USER_ID, CLAIMED, NOW, reflection(List.of(),
                List.of(new Supersession(stale, "Prefers imperial", 0.9, MemoryStatus.ACTIVE, List.of(userMessage))),
                List.of()));

        verify(userMemoryRepository, never()).saveAndFlush(any(UserMemory.class));
        verify(userMemoryRepository, never()).save(any(UserMemory.class));
    }

    @Test
    void aConfirmationAddsOnlyNewEvidenceAndActivatesAPendingInstruction() {
        UserMemory pending = memory(MemoryOrigin.INFERRED, MemoryStatus.PENDING, "style.lists", 0.8, NOW.minusDays(3));
        pending.setKind(MemoryKind.INSTRUCTION);
        ChatMessage alreadyCited = message(NOW.minusMinutes(9));

        when(userMemoryRepository.findByIdAndUserIdAndStatusIn(eq(pending.getId()), eq(USER_ID), anyCollection()))
                .thenReturn(Optional.of(pending));
        when(userMemoryEvidenceRepository.findByUserIdAndMemoryIdIn(USER_ID, Set.of(pending.getId())))
                .thenReturn(List.of(new UserMemoryEvidence(pending.getId(), USER_ID, CHAT_ID, alreadyCited.getId(), alreadyCited.getTimestamp())));
        when(userMemoryRepository.findByUserIdAndMemoryKeyAndStatus(USER_ID, "style.lists", MemoryStatus.ACTIVE)).thenReturn(Optional.empty());

        userMemoryService.applyReflection(CHAT_ID, USER_ID, CLAIMED, NOW, reflection(List.of(), List.of(),
                List.of(new Confirmation(pending, List.of(alreadyCited, userMessage), true))));

        verify(userMemoryEvidenceRepository).saveAll(evidenceCaptor.capture());
        assertThat(evidenceCaptor.getValue()).singleElement()
                .extracting(UserMemoryEvidence::getChatMessageId)
                .isEqualTo(userMessage.getId());
        assertThat(pending.getStatus()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(pending.getLastConfirmed()).isAfter(NOW.minusDays(1));
        assertThat(pending.getContent()).isEqualTo("content");
    }

    /**
     * What the user set deliberately is removed only by the user, so eviction at the cap only ever
     * takes the weakest inferred memory.
     */
    @Test
    void anAdditionAtTheCapEvictsTheLowestRankedInferredMemoryNeverAUserSetOne() {
        UserMemory authoredButStale = memory(MemoryOrigin.AUTHORED, MemoryStatus.ACTIVE, null, 0.1, NOW.minusYears(2));
        UserMemory strongInferred = memory(MemoryOrigin.INFERRED, MemoryStatus.ACTIVE, null, 0.95, NOW);
        UserMemory weakInferred = memory(MemoryOrigin.INFERRED, MemoryStatus.ACTIVE, null, 0.6, NOW.minusDays(200));

        when(userMemoryRepository.findByUserIdAndStatusInOrderByCreatedAsc(eq(USER_ID), anyCollection()))
                .thenReturn(List.of(authoredButStale, strongInferred, weakInferred));

        userMemoryService.applyReflection(CHAT_ID, USER_ID, CLAIMED, NOW, reflection(List.of(addition(MemoryStatus.ACTIVE)), List.of(), List.of()));

        verify(userMemoryRepository).delete(weakInferred);
        verify(userMemoryRepository, never()).delete(authoredButStale);
        verify(userMemoryRepository, never()).delete(strongInferred);
    }

    @Test
    void aPendingAdditionDoesNotCountAgainstTheCap() {
        userMemoryService.applyReflection(CHAT_ID, USER_ID, CLAIMED, NOW, reflection(List.of(addition(MemoryStatus.PENDING)), List.of(), List.of()));

        verify(userMemoryRepository, never()).findByUserIdAndStatusInOrderByCreatedAsc(any(), anyCollection());
        verify(userMemoryRepository, never()).delete(any(UserMemory.class));
        verify(userMemoryRepository).save(any(UserMemory.class));
    }

    private Addition addition(MemoryStatus status) {
        return new Addition(MemoryKind.FACT, null, "Lives in Leeds", 0.9, status, List.of(userMessage));
    }

    private static ValidatedReflection reflection(List<Addition> additions, List<Supersession> supersessions, List<Confirmation> confirmations) {
        return new ValidatedReflection(additions, supersessions, confirmations, Map.of());
    }

    private static ChatMessage message(ZonedDateTime timestamp) {
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setId(UUID.randomUUID());
        chatMessage.setChatId(CHAT_ID);
        chatMessage.setMessageType(MessageType.USER);
        chatMessage.setMessage("text");
        chatMessage.setTimestamp(timestamp);
        return chatMessage;
    }

    private static UserMemory memory(MemoryOrigin origin, MemoryStatus status, String memoryKey, double confidence, ZonedDateTime lastConfirmed) {
        UserMemory memory = new UserMemory();
        memory.setId(UUID.randomUUID());
        memory.setUserId(USER_ID);
        memory.setKind(MemoryKind.PREFERENCE);
        memory.setOrigin(origin);
        memory.setStatus(status);
        memory.setMemoryKey(memoryKey);
        memory.setContent("content");
        memory.setConfidence(confidence);
        memory.setCreated(lastConfirmed);
        memory.setUpdated(lastConfirmed);
        memory.setLastConfirmed(lastConfirmed);
        return memory;
    }
}
