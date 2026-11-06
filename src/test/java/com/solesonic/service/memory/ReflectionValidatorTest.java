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
import java.util.Collection;
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
                addition(MemoryKind.FACT, "The user banks with Acme", 0.9, assistantMessage.getId()),
                addition(MemoryKind.FACT, "The user lives in Leeds", 0.9, notInWindow, userMessage.getId())),
                null, null));

        assertThat(validated.additions()).singleElement()
                .satisfies(addition -> {
                    assertThat(addition.content()).isEqualTo("The user lives in Leeds");
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
                List.of(new MemorySupersession(target.getId(), "The user prefers imperial units", 0.9, List.of(userMessage.getId()))),
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
                List.of(new MemorySupersession(target.getId(), " The user prefers imperial units ", 0.9, List.of(laterUserMessage.getId()))),
                null));

        assertThat(validated.supersessions()).singleElement()
                .satisfies(supersession -> {
                    assertThat(supersession.target()).isSameAs(target);
                    assertThat(supersession.content()).isEqualTo("The user prefers imperial units");
                    assertThat(supersession.status()).isEqualTo(MemoryStatus.ACTIVE);
                });
    }

    @Test
    void anInstructionLearnedFromOneChatStaysPending() {
        ValidatedReflection validated = validate(context(List.of()), new ReflectionResult(
                List.of(addition(MemoryKind.INSTRUCTION, "The user wants no bullet lists", 0.9, userMessage.getId())), null, null));

        assertThat(validated.additions()).singleElement()
                .extracting(ValidatedReflection.Addition::status)
                .isEqualTo(MemoryStatus.PENDING);
    }

    /**
     * What one conversation asks for is usually about that conversation, so a preference needs a
     * second one before it is carried forward. A fact is true the first time it is said.
     */
    @Test
    void aPreferenceLearnedFromOneChatStaysPendingButAFactIsActive() {
        ValidatedReflection validated = validate(context(List.of()), new ReflectionResult(List.of(
                addition(MemoryKind.PREFERENCE, "The user prefers imperial units", 0.9, userMessage.getId()),
                addition(MemoryKind.FACT, "The user lives in Leeds", 0.9, userMessage.getId())),
                null, null));

        assertThat(validated.additions())
                .extracting(ValidatedReflection.Addition::status)
                .containsExactly(MemoryStatus.PENDING, MemoryStatus.ACTIVE);
    }

    /**
     * Every memory is a statement about the user. A proposal naming no user is general knowledge the
     * conversation turned up — usually the answer to a question the user asked.
     */
    @Test
    void dropsContentThatIsNotAboutTheUser() {
        ValidatedReflection validated = validate(context(List.of()), new ReflectionResult(
                List.of(addition(MemoryKind.FACT, "Leeds is in West Yorkshire", 0.9, userMessage.getId())), null, null));

        assertThat(validated.additions()).isEmpty();
        assertThat(validated.drops()).containsEntry(DropReason.NOT_ABOUT_USER, 1);
    }

    /**
     * One shared word is not grounding: a question shares its subject with its answer.
     */
    @Test
    void dropsContentSharingTooFewWordsWithWhatTheUserWrote() {
        ChatMessage question = message(MessageType.USER, NOW.minusMinutes(3), "What does MGP stand for?");

        ValidatedReflection validated = validate(windowOf(List.of(question)), new ReflectionResult(
                List.of(addition(MemoryKind.FACT, "The user's MGP means GPU memory", 0.9, question.getId())), null, null));

        assertThat(validated.additions()).isEmpty();
        assertThat(validated.drops()).containsEntry(DropReason.UNGROUNDED, 1);
    }

    @Test
    void dropsANumberTheUserNeverWrote() {
        ChatMessage question = message(MessageType.USER, NOW.minusMinutes(3), "When will UniFi Network 11 be released?");

        ValidatedReflection validated = validate(windowOf(List.of(question)), new ReflectionResult(
                List.of(addition(MemoryKind.FACT, "The user's UniFi Network 11 was released on October 12", 0.9, question.getId())),
                null, null));

        assertThat(validated.additions()).isEmpty();
        assertThat(validated.drops()).containsEntry(DropReason.UNGROUNDED, 1);
    }

    /**
     * A pasted prompt or log is worded for something else, so it can carry a fact the user stated in
     * it but never a preference.
     */
    @Test
    void aPastedMessageSupportsAFactButNotAPreference() {
        ChatMessage pasted = message(MessageType.USER, NOW.minusMinutes(3),
                "I prefer imperial units and I live in Leeds. " + "x".repeat(ReflectionValidator.PASTED_MESSAGE_CHARACTERS));

        ValidatedReflection validated = validate(windowOf(List.of(pasted)), new ReflectionResult(List.of(
                addition(MemoryKind.PREFERENCE, "The user prefers imperial units", 0.9, pasted.getId()),
                addition(MemoryKind.FACT, "The user lives in Leeds", 0.9, pasted.getId())),
                null, null));

        assertThat(validated.additions()).singleElement()
                .extracting(ValidatedReflection.Addition::kind)
                .isEqualTo(MemoryKind.FACT);
        assertThat(validated.drops())
                .containsEntry(DropReason.PASTED_EVIDENCE, 1)
                .containsEntry(DropReason.NO_EVIDENCE, 1);
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
                new MemoryAddition(MemoryKind.FACT, "  Job.Title ", "The user " + "x".repeat(250), 7.0, List.of(userMessage.getId())),
                new MemoryAddition(MemoryKind.FACT, null, "The user owns a dog", 0.2, List.of(userMessage.getId())),
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
                List.of(addition(MemoryKind.PREFERENCE, "units", "The user prefers imperial units", 0.9, userMessage.getId())), null, null));

        assertThat(validated.additions()).isEmpty();
        assertThat(validated.supersessions()).singleElement()
                .extracting(ValidatedReflection.Supersession::target)
                .isSameAs(holder);
    }

    /**
     * The user repeating a pending instruction in a later conversation is what activates it, whether
     * the model calls that a confirmation or adds the key again.
     */
    @Test
    void anAdditionRepeatingAPendingInstructionFromAnotherChatConfirmsAndActivatesIt() {
        UserMemory pending = memory(MemoryOrigin.INFERRED, MemoryStatus.PENDING, MemoryKind.INSTRUCTION, "style.lists");
        UserMemoryEvidence earlierChat = new UserMemoryEvidence(pending.getId(), USER_ID, OTHER_CHAT_ID,
                UUID.randomUUID(), NOW.minusDays(1));

        ValidatedReflection validated = validate(context(List.of(pending), List.of(earlierChat)), new ReflectionResult(
                List.of(addition(MemoryKind.INSTRUCTION, "style.lists", "The user wants no bullet lists", 0.9, userMessage.getId())), null, null));

        assertThat(validated.additions()).isEmpty();
        assertThat(validated.supersessions()).isEmpty();
        assertThat(validated.confirmations()).singleElement()
                .satisfies(confirmation -> {
                    assertThat(confirmation.target()).isSameAs(pending);
                    assertThat(confirmation.evidence()).containsExactly(userMessage);
                    assertThat(confirmation.activate()).isTrue();
                });
    }

    @Test
    void anAdditionRepeatingAPendingInstructionFromTheSameChatStaysASupersession() {
        UserMemory pending = memory(MemoryOrigin.INFERRED, MemoryStatus.PENDING, MemoryKind.INSTRUCTION, "style.lists");
        UserMemoryEvidence sameChat = new UserMemoryEvidence(pending.getId(), USER_ID, CHAT_ID,
                UUID.randomUUID(), NOW.minusDays(1));

        ValidatedReflection validated = validate(context(List.of(pending), List.of(sameChat)), new ReflectionResult(
                List.of(addition(MemoryKind.INSTRUCTION, "style.lists", "The user wants no bullet lists", 0.9, userMessage.getId())), null, null));

        assertThat(validated.confirmations()).isEmpty();
        assertThat(validated.supersessions()).singleElement()
                .extracting(ValidatedReflection.Supersession::status)
                .isEqualTo(MemoryStatus.PENDING);
    }

    @Test
    void anUngroundedRepeatOfAPendingInstructionIsDropped() {
        UserMemory pending = memory(MemoryOrigin.INFERRED, MemoryStatus.PENDING, MemoryKind.INSTRUCTION, "style.lists");
        UserMemoryEvidence earlierChat = new UserMemoryEvidence(pending.getId(), USER_ID, OTHER_CHAT_ID,
                UUID.randomUUID(), NOW.minusDays(1));

        ValidatedReflection validated = validate(context(List.of(pending), List.of(earlierChat)), new ReflectionResult(
                List.of(addition(MemoryKind.INSTRUCTION, "style.lists", "The user banks with Acme", 0.9, userMessage.getId())), null, null));

        assertThat(validated.confirmations()).isEmpty();
        assertThat(validated.drops()).containsEntry(DropReason.UNGROUNDED, 1);
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
                List.of(new MemorySupersession(target.getId(), "The user prefers imperial units", 0.9, List.of(userMessage.getId()))),
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
                addition(MemoryKind.FACT, "The user banks with Acme", 0.9, userMessage.getId()),
                addition(MemoryKind.FACT, "The user lives in Leeds", 0.9, userMessage.getId())),
                null, null));

        assertThat(validated.additions()).singleElement()
                .extracting(ValidatedReflection.Addition::content)
                .isEqualTo("The user lives in Leeds");
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

    private static ReflectionContext windowOf(Collection<ChatMessage> window) {
        return new ReflectionContext(USER_ID, CHAT_ID, List.copyOf(window), List.of(), List.of());
    }

    @SuppressWarnings("all")
    private static MemoryAddition addition(MemoryKind kind, String content, double confidence, UUID... evidenceMessageIds) {
        return new MemoryAddition(kind, null, content, confidence, List.of(evidenceMessageIds));
    }

    @SuppressWarnings("all")
    private static MemoryAddition addition(MemoryKind kind, String key, String content, double confidence, UUID evidenceMessageId) {
        return new MemoryAddition(kind, key, content, confidence, List.of(evidenceMessageId));
    }

    private static ChatMessage message(MessageType messageType, ZonedDateTime timestamp) {
        return message(messageType, timestamp, messageType == MessageType.USER
                ? "I moved to Leeds, switch to imperial units, no bullet lists please, xxxxxxxx"
                : "Your inbox has an email saying the user's bank is Acme");
    }

    private static ChatMessage message(MessageType messageType, ZonedDateTime timestamp, String text) {
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setId(UUID.randomUUID());
        chatMessage.setChatId(CHAT_ID);
        chatMessage.setMessageType(messageType);
        chatMessage.setMessage(text);
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
