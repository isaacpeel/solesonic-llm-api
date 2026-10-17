package com.solesonic.service.memory;

import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.model.memory.MemoryKind;
import com.solesonic.model.memory.MemoryOrigin;
import com.solesonic.model.memory.MemoryStatus;
import com.solesonic.model.memory.ReflectionResult;
import com.solesonic.model.memory.ReflectionResult.MemoryAddition;
import com.solesonic.model.memory.ReflectionResult.MemoryConfirmation;
import com.solesonic.model.memory.ReflectionResult.MemorySupersession;
import com.solesonic.model.memory.UserMemory;
import com.solesonic.model.memory.UserMemoryEvidence;
import com.solesonic.service.memory.ValidatedReflection.DropReason;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.MessageType;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReflectionValidatorTest {
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CHAT_ID = UUID.randomUUID();
    private static final UUID OTHER_CHAT_ID = UUID.randomUUID();
    private static final ZonedDateTime NOW = ZonedDateTime.now();

    private final ReflectionValidator reflectionValidator = new ReflectionValidator(100, 0.5);

    private final ChatMessage userMessage = message(MessageType.USER, NOW.minusMinutes(10));
    private final ChatMessage laterUserMessage = message(MessageType.USER, NOW.minusMinutes(5));
    private final ChatMessage assistantMessage = message(MessageType.ASSISTANT, NOW.minusMinutes(9));

    /**
     * Text the user did not write — an email, a web page, a tool result — only ever reaches the
     * transcript inside an ASSISTANT row, so refusing anything but USER evidence is what keeps it from
     * becoming a memory.
     */
    @Test
    void dropsEvidenceThatIsNotAUserMessageOfTheWindow() {
        UUID notInWindow = UUID.randomUUID();

        ValidatedReflection validated = validate(context(List.of()), new ReflectionResult(List.of(
                addition(MemoryKind.FACT, "Bank is Acme", 0.9, assistantMessage.getId()),
                addition(MemoryKind.FACT, "Lives in Leeds", 0.9, notInWindow, userMessage.getId())),
                null, null));

        assertThat(validated.additions()).singleElement()
                .satisfies(addition -> {
                    assertThat(addition.content()).isEqualTo("Lives in Leeds");
                    assertThat(addition.evidence()).containsExactly(userMessage);
                });
        assertThat(validated.drops())
                .containsEntry(DropReason.INVALID_EVIDENCE, 2)
                .containsEntry(DropReason.NO_EVIDENCE, 1);
    }

    /**
     * Another user's memory is never in the context, so an id naming one is exactly as unknown as an
     * id the model made up.
     */
    @Test
    void dropsAnUnknownOrForeignMemoryId() {
        UUID foreignMemoryId = UUID.randomUUID();

        ValidatedReflection validated = validate(context(List.of()), new ReflectionResult(null,
                List.of(new MemorySupersession(foreignMemoryId, "Prefers imperial", 0.9, List.of(userMessage.getId()))),
                List.of(new MemoryConfirmation(foreignMemoryId, List.of(userMessage.getId())))));

        assertThat(validated.supersessions()).isEmpty();
        assertThat(validated.confirmations()).isEmpty();
        assertThat(validated.drops()).containsEntry(DropReason.UNKNOWN_MEMORY, 2);
    }

    @Test
    void dropsSupersedingOrConfirmingAMemoryTheUserSet() {
        UserMemory remembered = memory(MemoryOrigin.REMEMBERED, MemoryStatus.ACTIVE, MemoryKind.PREFERENCE, "units");
        UserMemory authored = memory(MemoryOrigin.AUTHORED, MemoryStatus.ACTIVE, MemoryKind.FACT, "job");

        ValidatedReflection validated = validate(context(List.of(remembered, authored)), new ReflectionResult(null,
                List.of(new MemorySupersession(remembered.getId(), "Prefers imperial", 0.9, List.of(userMessage.getId()))),
                List.of(new MemoryConfirmation(authored.getId(), List.of(userMessage.getId())))));

        assertThat(validated.supersessions()).isEmpty();
        assertThat(validated.confirmations()).isEmpty();
        assertThat(validated.drops()).containsEntry(DropReason.USER_SET_MEMORY, 2);
    }

    /**
     * During backfill an old conversation can be read after a newer one. A statement made earlier
     * must never replace one made later.
     */
    @Test
    void dropsASupersessionWhoseEvidenceIsOlderThanTheTargets() {
        UserMemory target = memory(MemoryOrigin.INFERRED, MemoryStatus.ACTIVE, MemoryKind.PREFERENCE, "units");
        UserMemoryEvidence newerEvidence = new UserMemoryEvidence(target.getId(), USER_ID, OTHER_CHAT_ID,
                UUID.randomUUID(), NOW.minusMinutes(1));

        ValidatedReflection validated = validate(context(List.of(target), List.of(newerEvidence)), new ReflectionResult(null,
                List.of(new MemorySupersession(target.getId(), "Prefers imperial", 0.9, List.of(userMessage.getId()))),
                null));

        assertThat(validated.supersessions()).isEmpty();
        assertThat(validated.drops()).containsEntry(DropReason.OLDER_EVIDENCE, 1);
    }

    @Test
    void acceptsASupersessionWithNewerEvidenceKeepingTheTargetsStatus() {
        UserMemory target = memory(MemoryOrigin.INFERRED, MemoryStatus.ACTIVE, MemoryKind.PREFERENCE, "units");
        UserMemoryEvidence olderEvidence = new UserMemoryEvidence(target.getId(), USER_ID, OTHER_CHAT_ID,
                UUID.randomUUID(), NOW.minusDays(3));

        ValidatedReflection validated = validate(context(List.of(target), List.of(olderEvidence)), new ReflectionResult(null,
                List.of(new MemorySupersession(target.getId(), " Prefers imperial ", 0.9, List.of(laterUserMessage.getId()))),
                null));

        assertThat(validated.supersessions()).singleElement()
                .satisfies(supersession -> {
                    assertThat(supersession.target()).isSameAs(target);
                    assertThat(supersession.content()).isEqualTo("Prefers imperial");
                    assertThat(supersession.status()).isEqualTo(MemoryStatus.ACTIVE);
                });
    }

    @Test
    void anInstructionLearnedFromOneChatStaysPending() {
        ValidatedReflection validated = validate(context(List.of()), new ReflectionResult(
                List.of(addition(MemoryKind.INSTRUCTION, "Don't use bullet lists", 0.9, userMessage.getId())), null, null));

        assertThat(validated.additions()).singleElement()
                .extracting(ValidatedReflection.Addition::status)
                .isEqualTo(MemoryStatus.PENDING);
    }

    @Test
    void confirmingAPendingInstructionFromAnotherChatActivatesItButFromTheSameChatDoesNot() {
        UserMemory fromAnotherChat = memory(MemoryOrigin.INFERRED, MemoryStatus.PENDING, MemoryKind.INSTRUCTION, "style.lists");
        UserMemory fromThisChat = memory(MemoryOrigin.INFERRED, MemoryStatus.PENDING, MemoryKind.INSTRUCTION, "style.tone");

        List<UserMemoryEvidence> evidence = List.of(
                new UserMemoryEvidence(fromAnotherChat.getId(), USER_ID, OTHER_CHAT_ID, UUID.randomUUID(), NOW.minusDays(1)),
                new UserMemoryEvidence(fromThisChat.getId(), USER_ID, CHAT_ID, UUID.randomUUID(), NOW.minusDays(1)));

        ValidatedReflection validated = validate(context(List.of(fromAnotherChat, fromThisChat), evidence), new ReflectionResult(null, null,
                List.of(new MemoryConfirmation(fromAnotherChat.getId(), List.of(userMessage.getId())),
                        new MemoryConfirmation(fromThisChat.getId(), List.of(laterUserMessage.getId())))));

        assertThat(validated.confirmations()).hasSize(2);
        assertThat(validated.confirmations().get(0).activate()).isTrue();
        assertThat(validated.confirmations().get(1).activate()).isFalse();
    }

    @Test
    void appliesCapsAndClamps() {
        ValidatedReflection validated = validate(context(List.of()), new ReflectionResult(List.of(
                new MemoryAddition(MemoryKind.FACT, "  Job.Title ", "x".repeat(250), 7.0, List.of(userMessage.getId())),
                new MemoryAddition(MemoryKind.FACT, null, "Owns a dog", 0.2, List.of(userMessage.getId())),
                new MemoryAddition(MemoryKind.FACT, null, "   ", 0.9, List.of(userMessage.getId())),
                new MemoryAddition(null, null, "Kindless", 0.9, List.of(userMessage.getId()))),
                null, null));

        assertThat(validated.additions()).singleElement()
                .satisfies(addition -> {
                    assertThat(addition.content()).hasSize(100);
                    assertThat(addition.confidence()).isEqualTo(1.0);
                    assertThat(addition.key()).isEqualTo("job.title");
                });
        assertThat(validated.drops())
                .containsEntry(DropReason.LOW_CONFIDENCE, 1)
                .containsEntry(DropReason.BLANK_CONTENT, 1)
                .containsEntry(DropReason.MISSING_KIND, 1);
    }

    /**
     * Two active values for one key cannot coexist, so an addition naming a key that an inferred
     * memory already holds is that memory changing.
     */
    @Test
    void anAdditionForAnInferredMemorysKeyBecomesASupersession() {
        UserMemory holder = memory(MemoryOrigin.INFERRED, MemoryStatus.ACTIVE, MemoryKind.PREFERENCE, "units");

        ValidatedReflection validated = validate(context(List.of(holder)), new ReflectionResult(
                List.of(addition(MemoryKind.PREFERENCE, "units", "Prefers imperial", 0.9, userMessage.getId())), null, null));

        assertThat(validated.additions()).isEmpty();
        assertThat(validated.supersessions()).singleElement()
                .extracting(ValidatedReflection.Supersession::target)
                .isSameAs(holder);
    }

    @Test
    void anAdditionForAUserSetMemorysKeyIsDropped() {
        UserMemory holder = memory(MemoryOrigin.AUTHORED, MemoryStatus.ACTIVE, MemoryKind.PREFERENCE, "units");

        ValidatedReflection validated = validate(context(List.of(holder)), new ReflectionResult(
                List.of(addition(MemoryKind.PREFERENCE, "units", "Prefers imperial", 0.9, userMessage.getId())), null, null));

        assertThat(validated.additions()).isEmpty();
        assertThat(validated.supersessions()).isEmpty();
        assertThat(validated.drops()).containsEntry(DropReason.USER_SET_MEMORY, 1);
    }

    @Test
    void aSecondOperationOnTheSameMemoryIsDropped() {
        UserMemory target = memory(MemoryOrigin.INFERRED, MemoryStatus.ACTIVE, MemoryKind.PREFERENCE, "units");

        ValidatedReflection validated = validate(context(List.of(target)), new ReflectionResult(null,
                List.of(new MemorySupersession(target.getId(), "Prefers imperial", 0.9, List.of(userMessage.getId()))),
                List.of(new MemoryConfirmation(target.getId(), List.of(laterUserMessage.getId())))));

        assertThat(validated.supersessions()).hasSize(1);
        assertThat(validated.confirmations()).isEmpty();
        assertThat(validated.drops()).containsEntry(DropReason.DUPLICATE_TARGET, 1);
    }

    /**
     * Citing a real message the user wrote is not enough: the memory has to say something that
     * message said. Otherwise text lifted from a summarised email could be pinned to any user message.
     */
    @Test
    void dropsContentTheCitedMessageDoesNotSupport() {
        ValidatedReflection validated = validate(context(List.of()), new ReflectionResult(List.of(
                addition(MemoryKind.FACT, "Bank is Acme", 0.9, userMessage.getId()),
                addition(MemoryKind.FACT, "Lives in Leeds", 0.9, userMessage.getId())),
                null, null));

        assertThat(validated.additions()).singleElement()
                .extracting(ValidatedReflection.Addition::content)
                .isEqualTo("Lives in Leeds");
        assertThat(validated.drops()).containsEntry(DropReason.UNGROUNDED, 1);
    }

    @Test
    void anEmptyResultIsEmpty() {
        ValidatedReflection validated = validate(context(List.of()), new ReflectionResult(null, null, null));

        assertThat(validated.additions()).isEmpty();
        assertThat(validated.supersessions()).isEmpty();
        assertThat(validated.confirmations()).isEmpty();
        assertThat(validated.drops()).isEmpty();
    }

    private ValidatedReflection validate(ReflectionContext context, ReflectionResult result) {
        return reflectionValidator.validate(context, result);
    }

    private ReflectionContext context(List<UserMemory> memories) {
        return context(memories, List.of());
    }

    private ReflectionContext context(List<UserMemory> memories, List<UserMemoryEvidence> evidence) {
        return new ReflectionContext(USER_ID, CHAT_ID, List.of(userMessage, assistantMessage, laterUserMessage), memories, evidence);
    }

    private static MemoryAddition addition(MemoryKind kind, String content, double confidence, UUID... evidenceMessageIds) {
        return new MemoryAddition(kind, null, content, confidence, List.of(evidenceMessageIds));
    }

    private static MemoryAddition addition(MemoryKind kind, String key, String content, double confidence, UUID evidenceMessageId) {
        return new MemoryAddition(kind, key, content, confidence, List.of(evidenceMessageId));
    }

    private static ChatMessage message(MessageType messageType, ZonedDateTime timestamp) {
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setId(UUID.randomUUID());
        chatMessage.setChatId(CHAT_ID);
        chatMessage.setMessageType(messageType);
        chatMessage.setMessage(messageType == MessageType.USER
                ? "I moved to Leeds, switch to imperial units, no bullet lists please, xxxxxxxx"
                : "Your inbox has an email saying the user's bank is Acme");
        chatMessage.setTimestamp(timestamp);
        return chatMessage;
    }

    private static UserMemory memory(MemoryOrigin origin, MemoryStatus status, MemoryKind kind, String memoryKey) {
        UserMemory memory = new UserMemory();
        memory.setId(UUID.randomUUID());
        memory.setUserId(USER_ID);
        memory.setKind(kind);
        memory.setOrigin(origin);
        memory.setStatus(status);
        memory.setMemoryKey(memoryKey);
        memory.setContent("content");
        memory.setConfidence(0.8);
        return memory;
    }
}
