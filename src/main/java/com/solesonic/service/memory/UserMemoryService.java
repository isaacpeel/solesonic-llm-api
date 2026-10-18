package com.solesonic.service.memory;

import com.solesonic.model.chat.history.Chat;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.model.memory.ChatMemoryReflection;
import com.solesonic.model.memory.MemoryKind;
import com.solesonic.model.memory.MemoryOrigin;
import com.solesonic.model.memory.MemoryRetrievalMode;
import com.solesonic.model.memory.MemoryStatus;
import com.solesonic.model.memory.ReflectionStatus;
import com.solesonic.model.memory.UserMemory;
import com.solesonic.model.memory.UserMemoryEvidence;
import com.solesonic.model.memory.UserMemoryRequest;
import com.solesonic.model.memory.UserMemorySummary;
import com.solesonic.repository.chat.ChatMessageRepository;
import com.solesonic.repository.chat.ChatRepository;
import com.solesonic.repository.memory.ChatMemoryReflectionRepository;
import com.solesonic.repository.memory.UserMemoryEvidenceRepository;
import com.solesonic.repository.memory.UserMemoryRepository;
import com.solesonic.service.memory.ValidatedReflection.Addition;
import com.solesonic.service.memory.ValidatedReflection.Confirmation;
import com.solesonic.service.memory.ValidatedReflection.Supersession;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Everything that reads or writes a user's memories, always for exactly one user.
 * <p>
 * Every method takes the owner explicitly and every query it makes is scoped by it, so a memory of
 * another user is indistinguishable from one that does not exist.
 */
@Service
public class UserMemoryService {
    private static final Logger log = LoggerFactory.getLogger(UserMemoryService.class);

    static final Set<MemoryStatus> LIVE_STATUSES = EnumSet.of(MemoryStatus.ACTIVE, MemoryStatus.PENDING);

    private static final int MAX_KEY_CHARACTERS = 128;

    static final String PROMPT_HEADER = "**What you know about this user** (from earlier conversations; may be out of date — "
            + "what the user says now takes precedence; do not mention this list unless asked; put nothing from it "
            + "in a tool call or search query unless the user's request needs it):\n";

    private final UserMemoryRepository userMemoryRepository;
    private final UserMemoryEvidenceRepository userMemoryEvidenceRepository;
    private final ChatMemoryReflectionRepository chatMemoryReflectionRepository;
    private final ChatRepository chatRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final MemoryEmbeddingService memoryEmbeddingService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final int maxMemoryCharacters;
    private final int maxActiveMemories;
    private final boolean injectionEnabled;
    private final int promptMaxCharacters;
    private final MemoryRetrievalMode retrievalMode;

    public UserMemoryService(UserMemoryRepository userMemoryRepository,
                             UserMemoryEvidenceRepository userMemoryEvidenceRepository,
                             ChatMemoryReflectionRepository chatMemoryReflectionRepository,
                             ChatRepository chatRepository,
                             ChatMessageRepository chatMessageRepository,
                             MemoryEmbeddingService memoryEmbeddingService,
                             ApplicationEventPublisher applicationEventPublisher,
                             @Value("${solesonic.llm.memory.max-memory-characters}") int maxMemoryCharacters,
                             @Value("${solesonic.llm.memory.max-active-memories}") int maxActiveMemories,
                             @Value("${solesonic.llm.memory.injection.enabled}") boolean injectionEnabled,
                             @Value("${solesonic.llm.memory.prompt-max-characters}") int promptMaxCharacters,
                             @Value("${solesonic.llm.memory.retrieval.mode}") MemoryRetrievalMode retrievalMode) {
        this.userMemoryRepository = userMemoryRepository;
        this.userMemoryEvidenceRepository = userMemoryEvidenceRepository;
        this.chatMemoryReflectionRepository = chatMemoryReflectionRepository;
        this.chatRepository = chatRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.memoryEmbeddingService = memoryEmbeddingService;
        this.applicationEventPublisher = applicationEventPublisher;
        this.maxMemoryCharacters = maxMemoryCharacters;
        this.maxActiveMemories = maxActiveMemories;
        this.injectionEnabled = injectionEnabled;
        this.promptMaxCharacters = promptMaxCharacters;
        this.retrievalMode = retrievalMode;
    }

    /**
     * The status an inferred instruction is allowed: one conversation's request is usually about that
     * conversation, so it stays pending until its evidence spans two.
     */
    static MemoryStatus statusFor(MemoryKind kind, MemoryOrigin origin, long evidenceChats) {
        if (kind == MemoryKind.INSTRUCTION && origin == MemoryOrigin.INFERRED && evidenceChats < 2) {
            return MemoryStatus.PENDING;
        }

        return MemoryStatus.ACTIVE;
    }

    @Transactional(readOnly = true)
    public List<UserMemorySummary> list(UUID userId) {
        List<UserMemory> memories = userMemoryRepository.findByUserIdAndStatusInOrderByCreatedAsc(userId, LIVE_STATUSES);

        Map<UUID, List<UUID>> chatIdsByMemoryId = chatIdsByMemoryId(userId, memories);

        return memories.stream()
                .map(memory -> UserMemorySummary.of(memory, chatIdsByMemoryId.getOrDefault(memory.getId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public UserMemorySummary get(UUID userId, UUID memoryId) {
        UserMemory memory = userMemoryRepository.findByIdAndUserIdAndStatusIn(memoryId, userId, LIVE_STATUSES)
                .orElseThrow(() -> notFound(memoryId));

        return UserMemorySummary.of(memory, chatIdsByMemoryId(userId, List.of(memory)).getOrDefault(memoryId, List.of()));
    }

    /**
     * What the default chat route tells the model about the user for this turn, as a block for the
     * system prompt — never the user message, which the retrieval advisor rewrites. Only
     * {@code ACTIVE} memories, until {@code prompt-max-characters}, since the context window is shared
     * with history and retrieval.
     * <p>
     * Standing instructions come first and are carried on every turn, by confidence and recency: one
     * such as "answer in metric" applies whatever the message is about, and would rarely look similar
     * to it. The facts and preferences after them depend on {@code retrieval.mode} — under
     * {@link MemoryRetrievalMode#RELEVANT} only those relevant to {@code message} and the conversation
     * so far, most relevant and most trusted first; under {@link MemoryRetrievalMode#ALL} every one, by
     * confidence and recency.
     * <p>
     * Empty when injection is off, when the user has no active memories, and when they cannot be read,
     * and without facts and preferences when relevance cannot be worked out: a memory failure never
     * fails a turn. Deliberately not transactional, so a failed read cannot leave a transaction marked
     * for rollback behind it.
     *
     * @param chatId  the conversation whose earlier messages help decide relevance; may be null
     * @param message the user's own words this turn
     */
    public String promptBlock(UUID userId, UUID chatId, String message) {
        if (!injectionEnabled) {
            return "";
        }

        List<UserMemory> active;

        try {
            active = userMemoryRepository.findByUserIdAndStatusInOrderByCreatedAsc(userId, Set.of(MemoryStatus.ACTIVE));
        } catch (RuntimeException exception) {
            log.warn("Could not read memories for user {}, answering without them ({})", userId, exception.getClass().getSimpleName());
            return "";
        }

        List<UserMemory> owned = active.stream()
                .filter(memory -> memory.getStatus() == MemoryStatus.ACTIVE)
                .filter(memory -> userId.equals(memory.getUserId()))
                .toList();

        ZonedDateTime now = ZonedDateTime.now();

        List<UserMemory> instructions = owned.stream()
                .filter(memory -> memory.getKind() == MemoryKind.INSTRUCTION)
                .sorted(MemoryRanking.highestFirst(now))
                .toList();

        List<UserMemory> context = context(userId, chatId, message, owned, now);

        StringBuilder block = new StringBuilder(PROMPT_HEADER);
        List<UUID> carried = new ArrayList<>();

        boolean rendered = appendSection(block, "Standing instructions:\n", instructions, carried);
        rendered |= appendSection(block, "Context:\n", context, carried);

        log.info("Memory for user {} in chat {} ({}): {}", userId, chatId, retrievalMode, carried);

        return rendered ? block.toString() : "";
    }

    private List<UserMemory> context(UUID userId, UUID chatId, String message, List<UserMemory> owned, ZonedDateTime now) {
        List<UserMemory> candidates = owned.stream()
                .filter(memory -> memory.getKind() != MemoryKind.INSTRUCTION)
                .toList();

        if (retrievalMode == MemoryRetrievalMode.ALL) {
            return candidates.stream().sorted(MemoryRanking.highestFirst(now)).toList();
        }

        if (candidates.isEmpty()) {
            return List.of();
        }

        Map<UUID, Double> similarityById;

        try {
            similarityById = memoryEmbeddingService.relevant(userId, chatId, message);
        } catch (RuntimeException exception) {
            log.warn("Could not find relevant memories for user {}, answering with standing instructions only ({})",
                    userId, exception.getClass().getSimpleName());
            return List.of();
        }

        return candidates.stream()
                .filter(memory -> similarityById.containsKey(memory.getId()))
                .sorted(MemoryRanking.mostRelevantFirst(similarityById, now))
                .toList();
    }

    private boolean appendSection(StringBuilder block, String heading, List<UserMemory> memories, List<UUID> carried) {
        boolean headed = false;

        for (UserMemory memory : memories) {
            String line = "- " + memory.getContent().replaceAll("\\s+", " ").trim() + "\n";
            int needed = line.length() + (headed ? 0 : heading.length());

            if (block.length() + needed > promptMaxCharacters) {
                break;
            }

            if (!headed) {
                block.append(heading);
                headed = true;
            }

            block.append(line);
            carried.add(memory.getId());
        }

        return headed;
    }

    @Transactional
    public UserMemorySummary create(UUID userId, UserMemoryRequest request) {
        if (request.kind() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Memory kind must be given");
        }

        String content = requireContent(request.content());
        ZonedDateTime now = ZonedDateTime.now();

        UserMemory memory = new UserMemory();
        memory.setUserId(userId);
        memory.setKind(request.kind());
        memory.setOrigin(MemoryOrigin.AUTHORED);
        memory.setStatus(MemoryStatus.ACTIVE);
        memory.setContent(content);
        memory.setConfidence(1.0);
        memory.setCreated(now);
        memory.setUpdated(now);
        memory.setLastConfirmed(now);

        UserMemory saved = userMemoryRepository.save(memory);
        applicationEventPublisher.publishEvent(new MemoriesChangedEvent(userId));

        log.info("Created memory {} for user {}", saved.getId(), userId);

        return UserMemorySummary.of(saved, List.of());
    }

    /**
     * {@code /remember}: a memory the user asked for in a conversation. The owner is that
     * conversation's owner, read from the chat row, and the evidence is the {@code USER} message that
     * issued the command — already written before the turn reached the tool — so deleting the
     * conversation forgets it like any other. An active memory with the same key is superseded, which
     * the user may do even to one they authored: this is them changing it.
     */
    @Transactional
    public UserMemorySummary remember(UUID chatId, MemoryKind kind, String key, String content) {
        UUID userId = chatRepository.lockById(chatId)
                .map(Chat::getUserId)
                .orElseThrow(() -> new IllegalStateException("Chat not found: " + chatId));

        ChatMessage issuingMessage = chatMessageRepository.findFirstByChatIdAndMessageTypeOrderByTimestampDesc(chatId, MessageType.USER)
                .orElseThrow(() -> new IllegalStateException("No user message to remember from in chat " + chatId));

        String trimmed = requireContent(content);
        String memoryKey = normalizeKey(key);
        ZonedDateTime now = ZonedDateTime.now();

        UserMemory memory = new UserMemory();
        memory.setUserId(userId);
        memory.setKind(kind == null ? MemoryKind.FACT : kind);
        memory.setOrigin(MemoryOrigin.REMEMBERED);
        memory.setStatus(MemoryStatus.ACTIVE);
        memory.setMemoryKey(memoryKey);
        memory.setContent(trimmed);
        memory.setConfidence(1.0);
        memory.setCreated(now);
        memory.setUpdated(now);
        memory.setLastConfirmed(now);

        if (memoryKey != null) {
            userMemoryRepository.findByUserIdAndMemoryKeyAndStatus(userId, memoryKey, MemoryStatus.ACTIVE)
                    .ifPresent(holder -> {
                        holder.setStatus(MemoryStatus.SUPERSEDED);
                        holder.setUpdated(now);
                        userMemoryRepository.saveAndFlush(holder);
                        memory.setSupersedes(holder.getId());
                    });
        }

        UserMemory saved = userMemoryRepository.save(memory);

        userMemoryEvidenceRepository.save(new UserMemoryEvidence(saved.getId(), userId, chatId,
                issuingMessage.getId(), issuingMessage.getTimestamp()));
        applicationEventPublisher.publishEvent(new MemoriesChangedEvent(userId));

        return UserMemorySummary.of(saved, List.of(chatId));
    }

    /**
     * A memory key as stored: trimmed, lower case, at most {@value #MAX_KEY_CHARACTERS} characters,
     * or null when there is none.
     */
    static String normalizeKey(String key) {
        if (StringUtils.isBlank(key)) {
            return null;
        }

        return StringUtils.truncate(key.trim().toLowerCase(Locale.ROOT), MAX_KEY_CHARACTERS);
    }

    /**
     * Once the user edits a memory it is theirs: it becomes {@code AUTHORED}, and its evidence goes,
     * because it no longer depends on any conversation. The content is validated before the memory is
     * looked for, so a blank edit is a {@code 400} rather than the {@code 404} of a memory that was
     * never fetched.
     */
    @Transactional
    public UserMemorySummary update(UUID userId, UUID memoryId, UserMemoryRequest request) {
        if (request.kind() == null && request.content() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to change");
        }

        String content = request.content() == null ? null : requireContent(request.content());

        UserMemory memory = userMemoryRepository.findByIdAndUserIdAndStatusIn(memoryId, userId, LIVE_STATUSES)
                .orElseThrow(() -> notFound(memoryId));

        userMemoryEvidenceRepository.deleteByUserIdAndMemoryId(userId, memoryId);

        if (content != null) {
            memory.setContent(content);
        }

        if (request.kind() != null) {
            memory.setKind(request.kind());
        }

        if (memory.getStatus() != MemoryStatus.ACTIVE && keyHeldByAnotherActive(userId, memory)) {
            memory.setMemoryKey(null);
        }

        ZonedDateTime now = ZonedDateTime.now();
        memory.setOrigin(MemoryOrigin.AUTHORED);
        memory.setStatus(MemoryStatus.ACTIVE);
        memory.setConfidence(1.0);
        memory.setUpdated(now);
        memory.setLastConfirmed(now);

        UserMemory saved = userMemoryRepository.save(memory);
        applicationEventPublisher.publishEvent(new MemoriesChangedEvent(userId));

        log.info("Edited memory {} for user {}", memoryId, userId);

        return UserMemorySummary.of(saved, List.of());
    }

    /**
     * Removes that memory only. No reflection cursor is rewound, so it is not re-learned from
     * conversations already read, though it can be learned again if the user says it again.
     */
    @Transactional
    public void delete(UUID userId, UUID memoryId) {
        UserMemory memory = userMemoryRepository.findByIdAndUserId(memoryId, userId)
                .orElseThrow(() -> notFound(memoryId));

        userMemoryRepository.delete(memory);

        log.info("Deleted memory {} for user {}", memoryId, userId);
    }

    @Transactional
    public void deleteAll(UUID userId) {
        int deleted = userMemoryRepository.deleteAllByUserId(userId);

        log.info("Deleted {} memory(ies) for user {}", deleted, userId);
    }

    /**
     * Forgets a deleted conversation completely: its reflection cursor, its evidence, every memory
     * left with no evidence, and — if the conversation is where the user changed their mind — the
     * supersession it caused, by restoring what it replaced.
     * <p>
     * Locking and deleting the reflection row first is what makes a reflection of this chat still in
     * flight discard its result: it finds the row gone when it applies.
     */
    @Transactional
    public void forgetChat(UUID chatId, UUID userId) {
        chatMemoryReflectionRepository.findByChatIdAndUserId(chatId, userId)
                .ifPresent(chatMemoryReflectionRepository::delete);

        Set<UUID> memoryIds = memoryIds(userMemoryEvidenceRepository.findByUserIdAndChatId(userId, chatId));

        userMemoryEvidenceRepository.deleteByUserIdAndChatId(userId, chatId);

        int forgotten = forgetUnevidenced(userId, memoryIds);

        log.info("Forgot chat {} for user {}: {} memory(ies) removed", chatId, userId, forgotten);
    }

    /**
     * The same as {@link #forgetChat} for one message. The reflection row stays, because the rest of
     * the conversation remains, but it is locked, so a reflection of this chat in flight applies
     * either wholly before this forget or after it, when it no longer finds the message to cite.
     */
    @Transactional
    public void forgetChatMessage(UUID chatId, UUID chatMessageId, UUID userId) {
        chatMemoryReflectionRepository.findByChatIdAndUserId(chatId, userId);

        Set<UUID> memoryIds = memoryIds(userMemoryEvidenceRepository.findByUserIdAndChatMessageId(userId, chatMessageId));

        userMemoryEvidenceRepository.deleteByUserIdAndChatMessageId(userId, chatMessageId);

        int forgotten = forgetUnevidenced(userId, memoryIds);

        log.info("Forgot message {} of chat {} for user {}: {} memory(ies) removed", chatMessageId, chatId, userId, forgotten);
    }

    /**
     * Writes one validated reflection window and advances the chat's cursor, in one transaction.
     * <p>
     * The chat's reflection row is locked first. If it is gone, the chat was deleted while the model
     * was being asked, and the result is discarded: nothing learned from a forgotten conversation may
     * be written after it was forgotten. Each target is re-read live and user-scoped rather than
     * trusted from before the model call, since the user may have changed it in the meantime.
     * <p>
     * A result is also discarded when the row is no longer the claim it was read under — its cursor
     * moved or it is no longer in progress — which is what stops a window re-claimed by another
     * instance after a slow model call from being applied twice. Evidence citing a message deleted
     * meanwhile is dropped, and an operation left with none is not applied.
     *
     * @param claimedThrough the cursor the window was read from
     * @return false when the result was discarded
     */
    @Transactional
    public boolean applyReflection(UUID chatId,
                                   UUID userId,
                                   ZonedDateTime claimedThrough,
                                   ZonedDateTime reflectedThrough,
                                   ValidatedReflection reflection) {
        Optional<ChatMemoryReflection> claimed = chatMemoryReflectionRepository.findByChatIdAndUserId(chatId, userId);

        if (claimed.isEmpty()) {
            log.info("Chat {} was forgotten mid-reflection; discarding its result", chatId);
            return false;
        }

        ChatMemoryReflection chatMemoryReflection = claimed.get();

        if (chatMemoryReflection.getStatus() != ReflectionStatus.IN_PROGRESS
                || chatMemoryReflection.getReflectedThrough() == null
                || !chatMemoryReflection.getReflectedThrough().isEqual(claimedThrough)) {
            log.info("Chat {} was reflected on elsewhere meanwhile; discarding this result", chatId);
            return false;
        }

        Set<UUID> surviving = survivingMessageIds(reflection);

        for (Supersession supersession : reflection.supersessions()) {
            List<ChatMessage> evidence = surviving(supersession.evidence(), surviving);

            if (!evidence.isEmpty()) {
                applySupersession(userId, chatId, supersession, evidence);
            }
        }

        for (Confirmation confirmation : reflection.confirmations()) {
            List<ChatMessage> evidence = surviving(confirmation.evidence(), surviving);

            if (!evidence.isEmpty()) {
                applyConfirmation(userId, chatId, confirmation, evidence);
            }
        }

        for (Addition addition : reflection.additions()) {
            List<ChatMessage> evidence = surviving(addition.evidence(), surviving);

            if (!evidence.isEmpty()) {
                applyAddition(userId, chatId, addition, evidence);
            }
        }

        chatMemoryReflection.setReflectedThrough(reflectedThrough);
        chatMemoryReflection.setStatus(ReflectionStatus.COMPLETED);
        chatMemoryReflection.setAttempts(0);
        chatMemoryReflection.setUpdated(ZonedDateTime.now());
        chatMemoryReflectionRepository.save(chatMemoryReflection);
        applicationEventPublisher.publishEvent(new MemoriesChangedEvent(userId));

        return true;
    }

    /**
     * Never edits the old row: a new one carries the new content with only the superseding messages
     * as evidence, which is what lets forgetting this conversation restore the old one. The old row
     * is flushed as superseded first, because the new one takes over its key and the database will
     * not hold two active values for one key even for the length of a flush.
     */
    private Set<UUID> survivingMessageIds(ValidatedReflection reflection) {
        Set<UUID> cited = new HashSet<>();

        reflection.supersessions().forEach(supersession -> supersession.evidence().forEach(message -> cited.add(message.getId())));
        reflection.confirmations().forEach(confirmation -> confirmation.evidence().forEach(message -> cited.add(message.getId())));
        reflection.additions().forEach(addition -> addition.evidence().forEach(message -> cited.add(message.getId())));

        if (cited.isEmpty()) {
            return Set.of();
        }

        return chatMessageRepository.findAllById(cited)
                .stream()
                .map(ChatMessage::getId)
                .collect(Collectors.toSet());
    }

    private static List<ChatMessage> surviving(List<ChatMessage> evidence, Set<UUID> surviving) {
        return evidence.stream().filter(message -> surviving.contains(message.getId())).toList();
    }

    private void applySupersession(UUID userId, UUID chatId, Supersession supersession, List<ChatMessage> evidence) {
        Optional<UserMemory> live = liveInferred(userId, supersession.target().getId());

        if (live.isEmpty()) {
            return;
        }

        UserMemory target = live.get();
        ZonedDateTime now = ZonedDateTime.now();

        target.setStatus(MemoryStatus.SUPERSEDED);
        target.setUpdated(now);
        userMemoryRepository.saveAndFlush(target);

        UserMemory replacement = inferred(userId, target.getKind(), target.getMemoryKey(), supersession.content(),
                supersession.confidence(), supersession.status(), now);
        replacement.setSupersedes(target.getId());

        UserMemory saved = userMemoryRepository.save(replacement);

        saveEvidence(saved.getId(), userId, chatId, evidence);
    }

    private void applyConfirmation(UUID userId, UUID chatId, Confirmation confirmation, List<ChatMessage> evidence) {
        Optional<UserMemory> live = liveInferred(userId, confirmation.target().getId());

        if (live.isEmpty()) {
            return;
        }

        UserMemory target = live.get();

        Set<UUID> alreadyCited = userMemoryEvidenceRepository.findByUserIdAndMemoryIdIn(userId, Set.of(target.getId()))
                .stream()
                .map(UserMemoryEvidence::getChatMessageId)
                .collect(Collectors.toSet());

        saveEvidence(target.getId(), userId, chatId, evidence.stream()
                .filter(message -> !alreadyCited.contains(message.getId()))
                .toList());

        ZonedDateTime now = ZonedDateTime.now();
        target.setLastConfirmed(now);
        target.setUpdated(now);

        if (confirmation.activate() && target.getStatus() == MemoryStatus.PENDING && !keyHeldByAnotherActive(userId, target)) {
            evictIfFull(userId);
            target.setStatus(MemoryStatus.ACTIVE);
        }

        userMemoryRepository.save(target);
    }

    private void applyAddition(UUID userId, UUID chatId, Addition addition, List<ChatMessage> evidence) {
        UserMemory memory = inferred(userId, addition.kind(), addition.key(), addition.content(),
                addition.confidence(), addition.status(), ZonedDateTime.now());

        if (memory.getStatus() == MemoryStatus.ACTIVE) {
            if (keyHeldByAnotherActive(userId, memory)) {
                return;
            }

            evictIfFull(userId);
        }

        UserMemory saved = userMemoryRepository.save(memory);

        saveEvidence(saved.getId(), userId, chatId, evidence);
    }

    /**
     * Only ever evicts an inferred memory. What the user set deliberately is removed only by the
     * user, so a user with that many of their own simply goes past the cap.
     */
    private void evictIfFull(UUID userId) {
        List<UserMemory> active = userMemoryRepository.findByUserIdAndStatusInOrderByCreatedAsc(userId, Set.of(MemoryStatus.ACTIVE));

        if (active.size() < maxActiveMemories) {
            return;
        }

        ZonedDateTime now = ZonedDateTime.now();

        active.stream()
                .filter(memory -> memory.getOrigin() == MemoryOrigin.INFERRED)
                .min(MemoryRanking.lowestFirst(now))
                .ifPresent(evicted -> {
                    userMemoryRepository.delete(evicted);
                    userMemoryRepository.flush();

                    log.info("Evicted memory {} for user {} at the cap of {}", evicted.getId(), userId, maxActiveMemories);
                });
    }

    private Optional<UserMemory> liveInferred(UUID userId, UUID memoryId) {
        return userMemoryRepository.findByIdAndUserIdAndStatusIn(memoryId, userId, LIVE_STATUSES)
                .filter(memory -> memory.getOrigin() == MemoryOrigin.INFERRED);
    }

    private static UserMemory inferred(UUID userId,
                                       MemoryKind kind,
                                       String memoryKey,
                                       String content,
                                       double confidence,
                                       MemoryStatus status,
                                       ZonedDateTime now) {
        UserMemory memory = new UserMemory();
        memory.setUserId(userId);
        memory.setKind(kind);
        memory.setOrigin(MemoryOrigin.INFERRED);
        memory.setStatus(status);
        memory.setMemoryKey(memoryKey);
        memory.setContent(content);
        memory.setConfidence(confidence);
        memory.setCreated(now);
        memory.setUpdated(now);
        memory.setLastConfirmed(now);
        return memory;
    }

    private void saveEvidence(UUID memoryId, UUID userId, UUID chatId, List<ChatMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }

        userMemoryEvidenceRepository.saveAll(messages.stream()
                .map(message -> new UserMemoryEvidence(memoryId, userId, chatId, message.getId(), message.getTimestamp()))
                .toList());
    }

    private int forgetUnevidenced(UUID userId, Set<UUID> candidateIds) {
        if (candidateIds.isEmpty()) {
            return 0;
        }

        List<UserMemory> unevidenced = userMemoryRepository.findUnevidenced(userId, candidateIds, MemoryOrigin.AUTHORED);

        if (unevidenced.isEmpty()) {
            return 0;
        }

        Map<UUID, UserMemory> forgottenById = unevidenced.stream()
                .collect(Collectors.toMap(UserMemory::getId, Function.identity()));

        relinkSuccessors(userId, forgottenById);

        userMemoryRepository.deleteByUserIdAndIdIn(userId, forgottenById.keySet());

        restorePredecessors(userId, forgottenById);

        return forgottenById.size();
    }

    /**
     * A newer memory that superseded one being forgotten still stands, so it is pointed past the
     * forgotten one at whatever that replaced. Without this the database would null its link and a
     * later forget could no longer restore anything.
     */
    private void relinkSuccessors(UUID userId, Map<UUID, UserMemory> forgottenById) {
        for (UserMemory successor : userMemoryRepository.findByUserIdAndSupersedesIn(userId, forgottenById.keySet())) {
            if (forgottenById.containsKey(successor.getId())) {
                continue;
            }

            successor.setSupersedes(survivingPredecessor(forgottenById.get(successor.getSupersedes()), forgottenById));
            userMemoryRepository.save(successor);
        }
    }

    /**
     * Only a forgotten memory that was live — the head of its chain — restores anything. One that was
     * already superseded has a newer memory standing in front of it.
     */
    private void restorePredecessors(UUID userId, Map<UUID, UserMemory> forgottenById) {
        for (UserMemory forgotten : forgottenById.values()) {
            if (forgotten.getStatus() == MemoryStatus.SUPERSEDED) {
                continue;
            }

            UUID predecessorId = survivingPredecessor(forgotten, forgottenById);

            if (predecessorId == null) {
                continue;
            }

            userMemoryRepository.findByIdAndUserId(predecessorId, userId)
                    .filter(predecessor -> predecessor.getStatus() == MemoryStatus.SUPERSEDED)
                    .ifPresent(predecessor -> restore(userId, predecessor));
        }
    }

    private void restore(UUID userId, UserMemory predecessor) {
        List<UserMemoryEvidence> evidence = userMemoryEvidenceRepository.findByUserIdAndMemoryIdIn(userId, Set.of(predecessor.getId()));

        if (predecessor.getOrigin() != MemoryOrigin.AUTHORED && evidence.isEmpty()) {
            return;
        }

        long evidenceChats = evidence.stream().map(UserMemoryEvidence::getChatId).distinct().count();
        MemoryStatus restoredStatus = statusFor(predecessor.getKind(), predecessor.getOrigin(), evidenceChats);

        if (restoredStatus == MemoryStatus.ACTIVE && keyHeldByAnotherActive(userId, predecessor)) {
            return;
        }

        predecessor.setStatus(restoredStatus);
        predecessor.setUpdated(ZonedDateTime.now());
        userMemoryRepository.save(predecessor);

        log.info("Restored memory {} for user {}", predecessor.getId(), userId);
    }

    /**
     * The nearest memory down the chain that is not itself being forgotten, or null when the chain
     * runs out first.
     */
    private static UUID survivingPredecessor(UserMemory memory, Map<UUID, UserMemory> forgottenById) {
        Set<UUID> visited = new HashSet<>();
        UUID predecessorId = memory.getSupersedes();

        while (predecessorId != null && forgottenById.containsKey(predecessorId)) {
            if (!visited.add(predecessorId)) {
                return null;
            }

            predecessorId = forgottenById.get(predecessorId).getSupersedes();
        }

        return predecessorId;
    }

    private boolean keyHeldByAnotherActive(UUID userId, UserMemory memory) {
        if (memory.getMemoryKey() == null) {
            return false;
        }

        return userMemoryRepository.findByUserIdAndMemoryKeyAndStatus(userId, memory.getMemoryKey(), MemoryStatus.ACTIVE)
                .filter(holder -> !holder.getId().equals(memory.getId()))
                .isPresent();
    }

    private Map<UUID, List<UUID>> chatIdsByMemoryId(UUID userId, List<UserMemory> memories) {
        if (memories.isEmpty()) {
            return Map.of();
        }

        Set<UUID> memoryIds = memories.stream().map(UserMemory::getId).collect(Collectors.toSet());

        return userMemoryEvidenceRepository.findByUserIdAndMemoryIdIn(userId, memoryIds)
                .stream()
                .collect(Collectors.groupingBy(UserMemoryEvidence::getMemoryId,
                        Collectors.collectingAndThen(
                                Collectors.mapping(UserMemoryEvidence::getChatId, Collectors.toCollection(LinkedHashSet::new)),
                                List::copyOf)));
    }

    private static Set<UUID> memoryIds(Collection<UserMemoryEvidence> evidence) {
        return evidence.stream().map(UserMemoryEvidence::getMemoryId).collect(Collectors.toSet());
    }

    private String requireContent(String content) {
        if (StringUtils.isBlank(content)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Memory content must not be blank");
        }

        String trimmed = content.trim();

        if (trimmed.length() > maxMemoryCharacters) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Memory content must be " + maxMemoryCharacters + " characters or fewer");
        }

        return trimmed;
    }

    private static ResponseStatusException notFound(UUID memoryId) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Memory not found: " + memoryId);
    }
}
