package com.solesonic.service.memory;

import com.solesonic.model.memory.ChatMemoryReflection;
import com.solesonic.model.memory.MemoryKind;
import com.solesonic.model.memory.MemoryOrigin;
import com.solesonic.model.memory.MemoryStatus;
import com.solesonic.model.memory.UserMemory;
import com.solesonic.model.memory.UserMemoryEvidence;
import com.solesonic.model.memory.UserMemoryRequest;
import com.solesonic.model.memory.UserMemorySummary;
import com.solesonic.repository.memory.ChatMemoryReflectionRepository;
import com.solesonic.repository.memory.UserMemoryEvidenceRepository;
import com.solesonic.repository.memory.UserMemoryRepository;
import com.solesonic.service.interest.InterestOfferWithdrawal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserMemoryServiceTest {
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();
    private static final UUID CHAT_ID = UUID.randomUUID();
    private static final UUID OTHER_CHAT_ID = UUID.randomUUID();

    @Mock
    private UserMemoryRepository userMemoryRepository;

    @Mock
    private UserMemoryEvidenceRepository userMemoryEvidenceRepository;

    @Mock
    private ChatMemoryReflectionRepository chatMemoryReflectionRepository;

    @Mock
    private InterestOfferWithdrawal interestOfferWithdrawal;

    @Captor
    private ArgumentCaptor<Collection<UUID>> deletedMemoryIdsCaptor;

    private UserMemoryService userMemoryService;

    @BeforeEach
    void setUp() {
        userMemoryService = UserMemoryServiceFixture.service(userMemoryRepository,
                userMemoryEvidenceRepository,
                chatMemoryReflectionRepository,
                interestOfferWithdrawal);

        lenient().when(userMemoryRepository.save(any(UserMemory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void listsOnlyTheCallersActiveAndPendingMemoriesWithTheChatsTheyCameFrom() {
        UserMemory memory = memory(MemoryOrigin.INFERRED, MemoryStatus.ACTIVE, "units.preference", null);

        PageRequest pageRequest = PageRequest.of(0, 10);

        when(userMemoryRepository.findByUserIdAndStatusInOrderByCreatedAscIdAsc(eq(USER_ID), anyCollection(), eq(pageRequest)))
                .thenReturn(new PageImpl<>(List.of(memory), pageRequest, 11));
        when(userMemoryEvidenceRepository.findByUserIdAndMemoryIdIn(eq(USER_ID), anyCollection()))
                .thenReturn(List.of(evidence(memory, CHAT_ID), evidence(memory, CHAT_ID), evidence(memory, OTHER_CHAT_ID)));

        Page<UserMemorySummary> summaries = userMemoryService.list(USER_ID, pageRequest);

        assertThat(summaries.getContent()).singleElement()
                .satisfies(summary -> assertThat(summary.chatIds()).containsExactlyInAnyOrder(CHAT_ID, OTHER_CHAT_ID));
        assertThat(summaries.getTotalElements()).isEqualTo(11);

        verify(userMemoryRepository, never()).findByUserIdAndStatusInOrderByCreatedAscIdAsc(eq(OTHER_USER_ID), anyCollection(), any(Pageable.class));
    }

    @Test
    void createsAnAuthoredActiveMemoryForThePathUser() {
        UserMemorySummary summary = userMemoryService.create(USER_ID, new UserMemoryRequest(MemoryKind.PREFERENCE, "  Prefers metric units  "));

        ArgumentCaptor<UserMemory> saved = ArgumentCaptor.forClass(UserMemory.class);
        verify(userMemoryRepository).save(saved.capture());

        assertThat(saved.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getValue().getOrigin()).isEqualTo(MemoryOrigin.AUTHORED);
        assertThat(saved.getValue().getStatus()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(saved.getValue().getConfidence()).isEqualTo(1.0);
        assertThat(summary.content()).isEqualTo("Prefers metric units");
        assertThat(summary.chatIds()).isEmpty();
    }

    @Test
    void refusesABlankOrOversizedOrKindlessMemory() {
        assertBadRequest(() -> userMemoryService.create(USER_ID, new UserMemoryRequest(MemoryKind.FACT, "   ")));
        assertBadRequest(() -> userMemoryService.create(USER_ID, new UserMemoryRequest(MemoryKind.FACT, "x".repeat(UserMemoryServiceFixture.MAX_MEMORY_CHARACTERS + 1))));
        assertBadRequest(() -> userMemoryService.create(USER_ID, new UserMemoryRequest(null, "Has two kids")));

        verify(userMemoryRepository, never()).save(any(UserMemory.class));
    }

    @Test
    void editingAnotherUsersMemoryIsNotFoundAndWritesNothing() {
        UUID foreignMemoryId = UUID.randomUUID();

        when(userMemoryRepository.findByIdAndUserIdAndStatusIn(eq(foreignMemoryId), eq(USER_ID), anyCollection()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> userMemoryService.update(USER_ID, foreignMemoryId, new UserMemoryRequest(null, "mine now")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verify(userMemoryRepository, never()).save(any(UserMemory.class));
        verify(userMemoryEvidenceRepository, never()).deleteByUserIdAndMemoryId(any(), any());
    }

    /**
     * Once the user edits a memory it is theirs: it no longer depends on any conversation, so
     * deleting the one it was learned from must not take it away.
     */
    @Test
    void editingAMemoryMakesItAuthoredAndDropsItsEvidence() {
        UserMemory memory = memory(MemoryOrigin.INFERRED, MemoryStatus.ACTIVE, "units.preference", null);
        memory.setConfidence(0.6);

        when(userMemoryRepository.findByIdAndUserIdAndStatusIn(eq(memory.getId()), eq(USER_ID), anyCollection()))
                .thenReturn(Optional.of(memory));

        UserMemorySummary summary = userMemoryService.update(USER_ID, memory.getId(), new UserMemoryRequest(null, "Prefers imperial units"));

        verify(userMemoryEvidenceRepository).deleteByUserIdAndMemoryId(USER_ID, memory.getId());
        assertThat(summary.origin()).isEqualTo(MemoryOrigin.AUTHORED);
        assertThat(summary.status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(summary.confidence()).isEqualTo(1.0);
        assertThat(summary.content()).isEqualTo("Prefers imperial units");
        assertThat(summary.kind()).isEqualTo(MemoryKind.PREFERENCE);
    }

    @Test
    void activatingAPendingMemoryWhoseKeyIsTakenDropsTheKeyRatherThanFailing() {
        UserMemory pending = memory(MemoryOrigin.INFERRED, MemoryStatus.PENDING, "style.lists", null);
        UserMemory holder = memory(MemoryOrigin.INFERRED, MemoryStatus.ACTIVE, "style.lists", null);

        when(userMemoryRepository.findByIdAndUserIdAndStatusIn(eq(pending.getId()), eq(USER_ID), anyCollection()))
                .thenReturn(Optional.of(pending));
        when(userMemoryRepository.findByUserIdAndMemoryKeyAndStatus(USER_ID, "style.lists", MemoryStatus.ACTIVE))
                .thenReturn(Optional.of(holder));

        userMemoryService.update(USER_ID, pending.getId(), new UserMemoryRequest(MemoryKind.INSTRUCTION, null));

        assertThat(pending.getMemoryKey()).isNull();
        assertThat(pending.getStatus()).isEqualTo(MemoryStatus.ACTIVE);
    }

    @Test
    void deletingAnotherUsersMemoryIsNotFound() {
        UUID foreignMemoryId = UUID.randomUUID();

        when(userMemoryRepository.findByIdAndUserId(foreignMemoryId, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userMemoryService.delete(USER_ID, foreignMemoryId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verify(userMemoryRepository, never()).delete(any(UserMemory.class));
    }

    @Test
    void deletesOneOwnedMemory() {
        UserMemory memory = memory(MemoryOrigin.AUTHORED, MemoryStatus.ACTIVE, null, null);

        when(userMemoryRepository.findByIdAndUserId(memory.getId(), USER_ID)).thenReturn(Optional.of(memory));

        userMemoryService.delete(USER_ID, memory.getId());

        verify(userMemoryRepository).delete(memory);
        verify(interestOfferWithdrawal).withdrawOrphaned(USER_ID);
    }

    @Test
    void deletesAllOfTheCallersMemoriesOnly() {
        userMemoryService.deleteAll(USER_ID);

        verify(userMemoryRepository).deleteAllByUserId(USER_ID);
        verify(userMemoryRepository, never()).deleteAllByUserId(OTHER_USER_ID);
        verify(interestOfferWithdrawal).withdrawOrphaned(USER_ID);
        verify(interestOfferWithdrawal, never()).withdrawOrphaned(OTHER_USER_ID);
    }

    @Test
    void forgettingAChatDeletesItsReflectionRowEvidenceAndEveryMemoryEvidencedOnlyByIt() {
        ChatMemoryReflection reflection = new ChatMemoryReflection();
        reflection.setChatId(CHAT_ID);
        reflection.setUserId(USER_ID);

        UserMemory inferred = memory(MemoryOrigin.INFERRED, MemoryStatus.ACTIVE, "job", null);
        UserMemory remembered = memory(MemoryOrigin.REMEMBERED, MemoryStatus.ACTIVE, "kids", null);

        when(chatMemoryReflectionRepository.findByChatIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.of(reflection));
        when(userMemoryEvidenceRepository.findByUserIdAndChatId(USER_ID, CHAT_ID))
                .thenReturn(List.of(evidence(inferred, CHAT_ID), evidence(remembered, CHAT_ID)));
        when(userMemoryRepository.findUnevidenced(eq(USER_ID), anyCollection(), eq(MemoryOrigin.AUTHORED)))
                .thenReturn(List.of(inferred, remembered));

        userMemoryService.forgetChat(CHAT_ID, USER_ID);

        verify(chatMemoryReflectionRepository).delete(reflection);
        verify(userMemoryEvidenceRepository).deleteByUserIdAndChatId(USER_ID, CHAT_ID);
        assertThat(deletedMemoryIds()).containsExactlyInAnyOrder(inferred.getId(), remembered.getId());
        verify(interestOfferWithdrawal).withdrawOrphaned(USER_ID);
    }

    /**
     * A memory that another, still existing conversation also supports survives: only this chat's
     * evidence goes, so it stays attributable to the chat that is left.
     */
    @Test
    void aMemoryAlsoEvidencedByAnotherChatSurvives() {
        UserMemory memory = memory(MemoryOrigin.INFERRED, MemoryStatus.ACTIVE, "job", null);

        when(chatMemoryReflectionRepository.findByChatIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.empty());
        when(userMemoryEvidenceRepository.findByUserIdAndChatId(USER_ID, CHAT_ID)).thenReturn(List.of(evidence(memory, CHAT_ID)));
        when(userMemoryRepository.findUnevidenced(eq(USER_ID), anyCollection(), eq(MemoryOrigin.AUTHORED))).thenReturn(List.of());

        userMemoryService.forgetChat(CHAT_ID, USER_ID);

        verify(userMemoryEvidenceRepository).deleteByUserIdAndChatId(USER_ID, CHAT_ID);
        verify(userMemoryRepository, never()).deleteByUserIdAndIdIn(any(), anyCollection());
    }

    /**
     * If the deleted conversation is where the user changed their mind, forgetting it brings the
     * earlier memory back — through a chain of more than one supersession.
     */
    @Test
    void forgettingTheChatThatSupersededAMemoryRestoresThePredecessorThroughAChain() {
        UserMemory original = memory(MemoryOrigin.INFERRED, MemoryStatus.SUPERSEDED, "units.preference", null);
        UserMemory middle = memory(MemoryOrigin.INFERRED, MemoryStatus.SUPERSEDED, "units.preference", original.getId());
        UserMemory head = memory(MemoryOrigin.INFERRED, MemoryStatus.ACTIVE, "units.preference", middle.getId());

        when(chatMemoryReflectionRepository.findByChatIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.empty());
        when(userMemoryEvidenceRepository.findByUserIdAndChatId(USER_ID, CHAT_ID))
                .thenReturn(List.of(evidence(middle, CHAT_ID), evidence(head, CHAT_ID)));
        when(userMemoryRepository.findUnevidenced(eq(USER_ID), anyCollection(), eq(MemoryOrigin.AUTHORED)))
                .thenReturn(List.of(middle, head));
        when(userMemoryRepository.findByUserIdAndSupersedesIn(eq(USER_ID), anyCollection())).thenReturn(List.of());
        when(userMemoryRepository.findByIdAndUserId(original.getId(), USER_ID)).thenReturn(Optional.of(original));
        when(userMemoryEvidenceRepository.findByUserIdAndMemoryIdIn(USER_ID, Set.of(original.getId())))
                .thenReturn(List.of(evidence(original, OTHER_CHAT_ID), evidence(original, UUID.randomUUID())));
        when(userMemoryRepository.findByUserIdAndMemoryKeyAndStatus(USER_ID, "units.preference", MemoryStatus.ACTIVE))
                .thenReturn(Optional.empty());

        userMemoryService.forgetChat(CHAT_ID, USER_ID);

        assertThat(deletedMemoryIds()).containsExactlyInAnyOrder(middle.getId(), head.getId());
        assertThat(original.getStatus()).isEqualTo(MemoryStatus.ACTIVE);
        verify(userMemoryRepository).save(original);
    }

    /**
     * A restored inferred preference is held to the same rule as a new one: evidenced by a single
     * conversation, it comes back pending.
     */
    @Test
    void aRestoredPreferenceEvidencedByOneChatComesBackPending() {
        UserMemory original = memory(MemoryOrigin.INFERRED, MemoryStatus.SUPERSEDED, "units.preference", null);
        UserMemory head = memory(MemoryOrigin.INFERRED, MemoryStatus.ACTIVE, "units.preference", original.getId());

        when(chatMemoryReflectionRepository.findByChatIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.empty());
        when(userMemoryEvidenceRepository.findByUserIdAndChatId(USER_ID, CHAT_ID)).thenReturn(List.of(evidence(head, CHAT_ID)));
        when(userMemoryRepository.findUnevidenced(eq(USER_ID), anyCollection(), eq(MemoryOrigin.AUTHORED))).thenReturn(List.of(head));
        when(userMemoryRepository.findByUserIdAndSupersedesIn(eq(USER_ID), anyCollection())).thenReturn(List.of());
        when(userMemoryRepository.findByIdAndUserId(original.getId(), USER_ID)).thenReturn(Optional.of(original));
        when(userMemoryEvidenceRepository.findByUserIdAndMemoryIdIn(USER_ID, Set.of(original.getId())))
                .thenReturn(List.of(evidence(original, OTHER_CHAT_ID)));

        userMemoryService.forgetChat(CHAT_ID, USER_ID);

        assertThat(original.getStatus()).isEqualTo(MemoryStatus.PENDING);
        verify(userMemoryRepository).save(original);
    }

    @Test
    void noPredecessorIsRestoredOverAnActiveMemoryWithTheSameKey() {
        UserMemory original = memory(MemoryOrigin.INFERRED, MemoryStatus.SUPERSEDED, "units.preference", null);
        UserMemory head = memory(MemoryOrigin.INFERRED, MemoryStatus.ACTIVE, "units.preference", original.getId());
        UserMemory holder = memory(MemoryOrigin.AUTHORED, MemoryStatus.ACTIVE, "units.preference", null);

        when(chatMemoryReflectionRepository.findByChatIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.empty());
        when(userMemoryEvidenceRepository.findByUserIdAndChatId(USER_ID, CHAT_ID)).thenReturn(List.of(evidence(head, CHAT_ID)));
        when(userMemoryRepository.findUnevidenced(eq(USER_ID), anyCollection(), eq(MemoryOrigin.AUTHORED))).thenReturn(List.of(head));
        when(userMemoryRepository.findByUserIdAndSupersedesIn(eq(USER_ID), anyCollection())).thenReturn(List.of());
        when(userMemoryRepository.findByIdAndUserId(original.getId(), USER_ID)).thenReturn(Optional.of(original));
        when(userMemoryEvidenceRepository.findByUserIdAndMemoryIdIn(USER_ID, Set.of(original.getId())))
                .thenReturn(List.of(evidence(original, OTHER_CHAT_ID), evidence(original, UUID.randomUUID())));
        when(userMemoryRepository.findByUserIdAndMemoryKeyAndStatus(USER_ID, "units.preference", MemoryStatus.ACTIVE))
                .thenReturn(Optional.of(holder));

        userMemoryService.forgetChat(CHAT_ID, USER_ID);

        assertThat(original.getStatus()).isEqualTo(MemoryStatus.SUPERSEDED);
        verify(userMemoryRepository, never()).save(original);
    }

    /**
     * Forgetting a memory in the middle of a chain must not resurrect anything: the newer memory
     * still stands. It is re-pointed past the deleted one so a later forget can still restore.
     */
    @Test
    void forgettingASupersededMiddleRelinksItsSuccessorAndRestoresNothing() {
        UserMemory original = memory(MemoryOrigin.INFERRED, MemoryStatus.SUPERSEDED, "units.preference", null);
        UserMemory middle = memory(MemoryOrigin.INFERRED, MemoryStatus.SUPERSEDED, "units.preference", original.getId());
        UserMemory head = memory(MemoryOrigin.INFERRED, MemoryStatus.ACTIVE, "units.preference", middle.getId());

        when(chatMemoryReflectionRepository.findByChatIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.empty());
        when(userMemoryEvidenceRepository.findByUserIdAndChatId(USER_ID, CHAT_ID)).thenReturn(List.of(evidence(middle, CHAT_ID)));
        when(userMemoryRepository.findUnevidenced(eq(USER_ID), anyCollection(), eq(MemoryOrigin.AUTHORED))).thenReturn(List.of(middle));
        when(userMemoryRepository.findByUserIdAndSupersedesIn(eq(USER_ID), anyCollection())).thenReturn(List.of(head));

        userMemoryService.forgetChat(CHAT_ID, USER_ID);

        assertThat(head.getSupersedes()).isEqualTo(original.getId());
        assertThat(original.getStatus()).isEqualTo(MemoryStatus.SUPERSEDED);
        verify(userMemoryRepository).save(head);
        verify(userMemoryRepository, never()).findByIdAndUserId(original.getId(), USER_ID);
    }

    @Test
    void forgettingOneMessageForgetsOnlyWhatThatMessageEvidencedAndKeepsTheReflectionRow() {
        UUID chatMessageId = UUID.randomUUID();
        UserMemory memory = memory(MemoryOrigin.INFERRED, MemoryStatus.ACTIVE, "job", null);

        when(userMemoryEvidenceRepository.findByUserIdAndChatMessageId(USER_ID, chatMessageId))
                .thenReturn(List.of(evidence(memory, CHAT_ID)));
        when(userMemoryRepository.findUnevidenced(eq(USER_ID), anyCollection(), eq(MemoryOrigin.AUTHORED))).thenReturn(List.of(memory));
        when(userMemoryRepository.findByUserIdAndSupersedesIn(eq(USER_ID), anyCollection())).thenReturn(List.of());

        userMemoryService.forgetChatMessage(CHAT_ID, chatMessageId, USER_ID);

        verify(userMemoryEvidenceRepository).deleteByUserIdAndChatMessageId(USER_ID, chatMessageId);
        verify(userMemoryEvidenceRepository, never()).deleteByUserIdAndChatId(any(), any());
        verify(chatMemoryReflectionRepository, never()).delete(any(ChatMemoryReflection.class));
        assertThat(deletedMemoryIds()).containsExactly(memory.getId());
        verify(interestOfferWithdrawal).withdrawOrphaned(USER_ID);
    }

    @Test
    void forgettingAChatWithNothingLearnedTouchesNoMemories() {
        when(chatMemoryReflectionRepository.findByChatIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.empty());
        when(userMemoryEvidenceRepository.findByUserIdAndChatId(USER_ID, CHAT_ID)).thenReturn(List.of());

        userMemoryService.forgetChat(CHAT_ID, USER_ID);

        verify(userMemoryRepository, never()).findUnevidenced(any(), anyCollection(), any());
        verify(userMemoryRepository, never()).deleteByUserIdAndIdIn(any(), anyCollection());
        verify(interestOfferWithdrawal, never()).withdrawOrphaned(any());
    }

    /**
     * Every query the forget path makes names the deleting user; nothing it does can reach another
     * user's rows even when the two share nothing but a chat id they could not both own.
     */
    @Test
    void forgettingAChatNeverQueriesAnotherUsersRows() {
        when(chatMemoryReflectionRepository.findByChatIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.empty());
        when(userMemoryEvidenceRepository.findByUserIdAndChatId(USER_ID, CHAT_ID)).thenReturn(List.of());

        userMemoryService.forgetChat(CHAT_ID, USER_ID);

        verify(chatMemoryReflectionRepository, never()).findByChatIdAndUserId(CHAT_ID, OTHER_USER_ID);
        verify(userMemoryEvidenceRepository, never()).findByUserIdAndChatId(OTHER_USER_ID, CHAT_ID);
        verify(userMemoryEvidenceRepository, never()).deleteByUserIdAndChatId(OTHER_USER_ID, CHAT_ID);
    }

    private Collection<UUID> deletedMemoryIds() {
        verify(userMemoryRepository).deleteByUserIdAndIdIn(eq(USER_ID), deletedMemoryIdsCaptor.capture());
        return deletedMemoryIdsCaptor.getValue();
    }

    private static void assertBadRequest(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    static UserMemory memory(MemoryOrigin origin, MemoryStatus status, String memoryKey, UUID supersedes) {
        UserMemory memory = new UserMemory();
        memory.setId(UUID.randomUUID());
        memory.setUserId(USER_ID);
        memory.setKind(MemoryKind.PREFERENCE);
        memory.setOrigin(origin);
        memory.setStatus(status);
        memory.setMemoryKey(memoryKey);
        memory.setContent("content");
        memory.setConfidence(0.8);
        memory.setCreated(ZonedDateTime.now());
        memory.setUpdated(ZonedDateTime.now());
        memory.setLastConfirmed(ZonedDateTime.now());
        memory.setSupersedes(supersedes);
        return memory;
    }

    static UserMemoryEvidence evidence(UserMemory memory, UUID chatId) {
        return new UserMemoryEvidence(memory.getId(), USER_ID, chatId, UUID.randomUUID(), ZonedDateTime.now());
    }
}
