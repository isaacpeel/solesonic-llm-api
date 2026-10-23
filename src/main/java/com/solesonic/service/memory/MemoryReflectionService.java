package com.solesonic.service.memory;

import com.solesonic.model.chat.history.Chat;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.model.memory.ChatMemoryReflection;
import com.solesonic.model.memory.ReflectionResult;
import com.solesonic.model.memory.UserMemory;
import com.solesonic.model.memory.UserMemoryEvidence;
import com.solesonic.repository.chat.ChatMessageRepository;
import com.solesonic.repository.chat.ChatRepository;
import com.solesonic.repository.memory.UserMemoryEvidenceRepository;
import com.solesonic.repository.memory.UserMemoryRepository;
import com.solesonic.service.user.UserProfileService;
import com.solesonic.util.PromptQuoting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.solesonic.config.openai.MemoryOpenAiConfig.MEMORY_CHAT_MODEL;

/**
 * Learns from one quiet conversation at a time: reads the next window of it, asks the memory model
 * what is worth keeping about its owner, validates that against the owner in code, and applies it.
 * <p>
 * Reflection works on exactly one user at a time. The owner comes from the chat row and nowhere else
 * ({@code chat_message} has no user), the transcript is that chat's, and the memories the model is
 * shown are that owner's alone.
 */
@Service
public class MemoryReflectionService {
    private static final Logger log = LoggerFactory.getLogger(MemoryReflectionService.class);

    private static final ReflectionResult NOTHING = new ReflectionResult(List.of(), List.of(), List.of());

    private static final int ASSISTANT_EXCERPT_CHARACTERS = 300;

    private final ChatMemoryReflectionService chatMemoryReflectionService;
    private final ChatRepository chatRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final UserMemoryRepository userMemoryRepository;
    private final UserMemoryEvidenceRepository userMemoryEvidenceRepository;
    private final ReflectionValidator reflectionValidator;
    private final ReflectionDeduplicator reflectionDeduplicator;
    private final UserMemoryService userMemoryService;
    private final MemoryEmbeddingService memoryEmbeddingService;
    private final UserProfileService userProfileService;
    private final ChatClient memoryChatClient;
    private final String reflectionPrompt;
    private final Duration idle;
    private final int batchSize;
    private final int maxAttempts;
    private final int maxTranscriptCharacters;
    private final int shownMemories;

    public MemoryReflectionService(ChatMemoryReflectionService chatMemoryReflectionService,
                                   ChatRepository chatRepository,
                                   ChatMessageRepository chatMessageRepository,
                                   UserMemoryRepository userMemoryRepository,
                                   UserMemoryEvidenceRepository userMemoryEvidenceRepository,
                                   ReflectionValidator reflectionValidator,
                                   ReflectionDeduplicator reflectionDeduplicator,
                                   UserMemoryService userMemoryService,
                                   MemoryEmbeddingService memoryEmbeddingService,
                                   UserProfileService userProfileService,
                                   @Qualifier(MEMORY_CHAT_MODEL) ChatModel memoryChatModel,
                                   @Value("classpath:prompts/memory-reflection-prompt.st") Resource reflectionPromptResource,
                                   @Value("${solesonic.llm.memory.reflection.idle-minutes}") long idleMinutes,
                                   @Value("${solesonic.llm.memory.reflection.batch-size}") int batchSize,
                                   @Value("${solesonic.llm.memory.reflection.max-attempts}") int maxAttempts,
                                   @Value("${solesonic.llm.memory.reflection.max-transcript-characters}") int maxTranscriptCharacters,
                                   @Value("${solesonic.llm.memory.reflection.shown-memories}") int shownMemories) {
        this.chatMemoryReflectionService = chatMemoryReflectionService;
        this.chatRepository = chatRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.userMemoryRepository = userMemoryRepository;
        this.userMemoryEvidenceRepository = userMemoryEvidenceRepository;
        this.reflectionValidator = reflectionValidator;
        this.reflectionDeduplicator = reflectionDeduplicator;
        this.userMemoryService = userMemoryService;
        this.memoryEmbeddingService = memoryEmbeddingService;
        this.userProfileService = userProfileService;
        this.memoryChatClient = ChatClient.create(memoryChatModel);
        this.reflectionPrompt = read(reflectionPromptResource);
        this.idle = Duration.ofMinutes(idleMinutes);
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.maxTranscriptCharacters = maxTranscriptCharacters;
        this.shownMemories = shownMemories;
    }

    /**
     * Claims a batch of quiet conversations and reflects on them sequentially, one model call at a
     * time, so reflection never takes more than one slot on the model server away from live chat.
     */
    public ReflectionRun reflectEligible() {
        List<ChatMemoryReflection> claimed = chatMemoryReflectionService.claim(ZonedDateTime.now().minus(idle), batchSize);

        ReflectionRun run = new ReflectionRun();

        for (ChatMemoryReflection reflection : claimed) {
            reflect(reflection, run);
        }

        if (!claimed.isEmpty()) {
            log.info("Memory reflection run over {} chat(s): {}", claimed.size(), run);
        }

        return run;
    }

    private void reflect(ChatMemoryReflection claimed, ReflectionRun run) {
        UUID chatId = claimed.getChatId();

        try {
            Optional<Chat> chat = chatRepository.findById(chatId);

            if (chat.isEmpty() || !claimed.getUserId().equals(chat.get().getUserId())) {
                chatMemoryReflectionService.remove(chatId, claimed.getUserId());
                run.discarded();
                return;
            }

            UUID userId = chat.get().getUserId();

            Window window = window(chatMessageRepository.findByChatIdAndTimestampAfterOrderByTimestampAsc(chatId, claimed.getReflectedThrough()));

            if (window.reflectedThrough() == null) {
                chatMemoryReflectionService.release(chatId, userId);
                run.discarded();
                return;
            }

            ValidatedReflection validated = window.hasUserMessage()
                    ? validated(userId, chatId, window.messages())
                    : ValidatedReflection.empty();

            if (userMemoryService.applyReflection(chatId, userId, claimed.getReflectedThrough(), window.reflectedThrough(), validated)) {
                run.applied(validated);
            } else {
                run.discarded();
            }
        } catch (RuntimeException exception) {
            log.warn("Memory reflection failed for chat {} ({})", chatId, exception.getClass().getSimpleName());
            log.debug("Memory reflection failure for chat {}", chatId, exception);

            chatMemoryReflectionService.recordFailure(chatId, claimed.getUserId(), maxAttempts);
            run.failed();
        }
    }

    /**
     * The model is shown only the memories nearest the conversation, but every live memory stays in
     * the context it is validated against, so a key or a duplicate it was not shown still resolves to
     * the memory that holds it.
     */
    private ValidatedReflection validated(UUID userId, UUID chatId, List<ChatMessage> messages) {
        List<UserMemory> memories = userMemoryRepository.findByUserIdAndStatusInOrderByCreatedAsc(userId, UserMemoryService.LIVE_STATUSES);

        Set<UUID> memoryIds = memories.stream().map(UserMemory::getId).collect(Collectors.toSet());

        List<UserMemoryEvidence> evidence = memoryIds.isEmpty()
                ? List.of()
                : userMemoryEvidenceRepository.findByUserIdAndMemoryIdIn(userId, memoryIds);

        ReflectionContext context = new ReflectionContext(userId, chatId, messages, memories, evidence);

        ReflectionResult result = memoryChatClient.prompt()
                .system(reflectionPrompt)
                .user(render(context, shown(userId, memories, messages), displayName(userId)))
                .call()
                .entity(ReflectionResult.class);

        ReflectionResult proposed = reflectionDeduplicator.deduplicate(userId, result == null ? NOTHING : result, memories);

        return reflectionValidator.validate(context, proposed);
    }

    /**
     * All of the user's live memories when there are few enough, otherwise those nearest what the
     * user said in this window — or all of them again when the search fails, since a longer prompt is
     * better than a reflection that cannot see a memory it is about to repeat.
     */
    private List<UserMemory> shown(UUID userId, List<UserMemory> memories, List<ChatMessage> messages) {
        if (memories.size() <= shownMemories) {
            return memories;
        }

        String said = messages.stream()
                .filter(message -> message.getMessageType() == MessageType.USER)
                .map(MemoryReflectionService::shown)
                .collect(Collectors.joining("\n"));

        try {
            Set<UUID> nearest = new HashSet<>(memoryEmbeddingService.nearestLiveIds(userId, said, shownMemories));

            return memories.stream().filter(memory -> nearest.contains(memory.getId())).toList();
        } catch (RuntimeException exception) {
            log.warn("Could not find the memories nearest a conversation for user {}, showing all ({})",
                    userId, exception.getClass().getSimpleName());
            return memories;
        }
    }

    /**
     * @return the user's name, or null when it is not known or cannot be read
     */
    private String displayName(UUID userId) {
        try {
            return userProfileService.displayName(userId).orElse(null);
        } catch (RuntimeException exception) {
            log.warn("Could not read the display name of user {} ({})", userId, exception.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * The next window of a conversation, in order, never truncated: messages are taken until the next
     * would pass {@code max-transcript-characters}, and the cursor advances only to the last one taken,
     * so the rest is picked up by a later run and nothing is skipped. {@code SYSTEM} rows are UI
     * notices; they are never shown, but the cursor moves past them.
     */
    Window window(List<ChatMessage> unreflected) {
        List<ChatMessage> messages = new ArrayList<>();
        ZonedDateTime reflectedThrough = null;
        boolean hasUserMessage = false;
        int characters = 0;

        for (ChatMessage message : unreflected) {
            MessageType messageType = message.getMessageType();

            if (messageType == MessageType.USER || messageType == MessageType.ASSISTANT) {
                int length = shown(message).length();

                if (!messages.isEmpty()
                        && characters + length > maxTranscriptCharacters
                        && message.getTimestamp().isAfter(reflectedThrough)) {
                    break;
                }

                messages.add(message);
                characters += length;
                hasUserMessage |= messageType == MessageType.USER;
            }

            reflectedThrough = message.getTimestamp();
        }

        return new Window(List.copyOf(messages), reflectedThrough, hasUserMessage);
    }

    /**
     * Each message is one line, its text a quoted string with every line break escaped, so nothing
     * inside a message — an email, a web page — can start a line of its own that looks like a USER
     * label.
     *
     * @param displayName the user's own name, or null when it is not known
     */
    private static String render(ReflectionContext context, List<UserMemory> shownMemories, String displayName) {
        StringBuilder rendered = new StringBuilder();

        if (displayName != null) {
            rendered.append("The user's name: ").append(PromptQuoting.quote(displayName)).append("\n\n");
        }

        rendered.append("Existing memories:\n");

        if (shownMemories.isEmpty()) {
            rendered.append("(none)\n");
        }

        for (UserMemory memory : shownMemories) {
            rendered.append("- id=").append(memory.getId())
                    .append(" kind=").append(memory.getKind())
                    .append(" key=").append(Objects.toString(memory.getMemoryKey(), "none"))
                    .append(" status=").append(memory.getStatus())
                    .append(": ").append(PromptQuoting.quote(memory.getContent()))
                    .append('\n');
        }

        rendered.append("\nConversation, oldest first:\n");

        for (ChatMessage message : context.window()) {
            rendered.append(message.getMessageType())
                    .append(" id=").append(message.getId())
                    .append(" at=").append(message.getTimestamp());

            if (message.getMessageType() == MessageType.USER && ReflectionValidator.pasted(message)) {
                rendered.append(" pasted=true");
            }

            rendered.append(": ").append(PromptQuoting.quote(shown(message)))
                    .append('\n');
        }

        return rendered.toString();
    }

    /**
     * The user's words in full unless they were pasted; an assistant reply only as an excerpt. A
     * reply is where mail, web pages and tool results reach the transcript, and the model needs only
     * enough of it to read what the user was answering — not a whole summarised email to be talked
     * into remembering. A pasted log or prompt is cut for the same reason.
     */
    private static String shown(ChatMessage message) {
        String text = Objects.toString(message.getMessage(), "");

        int limit = message.getMessageType() == MessageType.ASSISTANT
                ? ASSISTANT_EXCERPT_CHARACTERS
                : ReflectionValidator.PASTED_MESSAGE_CHARACTERS;

        if (text.length() <= limit) {
            return text;
        }

        return text.substring(0, limit) + " [truncated]";
    }

    private static String read(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    record Window(List<ChatMessage> messages, ZonedDateTime reflectedThrough, boolean hasUserMessage) {
    }
}
