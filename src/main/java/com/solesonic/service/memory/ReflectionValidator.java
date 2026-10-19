package com.solesonic.service.memory;

import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.model.memory.MemoryOrigin;
import com.solesonic.model.memory.MemoryStatus;
import com.solesonic.model.memory.ReflectionResult;
import com.solesonic.model.memory.ReflectionResult.MemoryAddition;
import com.solesonic.model.memory.ReflectionResult.MemoryConfirmation;
import com.solesonic.model.memory.ReflectionResult.MemorySupersession;
import com.solesonic.model.memory.UserMemory;
import com.solesonic.model.memory.UserMemoryEvidence;
import com.solesonic.service.memory.ValidatedReflection.Addition;
import com.solesonic.service.memory.ValidatedReflection.Confirmation;
import com.solesonic.service.memory.ValidatedReflection.DropReason;
import com.solesonic.service.memory.ValidatedReflection.Supersession;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The code-side guard on what the memory model proposes, which matters more than the prompt.
 * <p>
 * Evidence must be a {@code USER} message of the window being reflected. Mail, Jira, Confluence, web
 * pages, retrieved chunks and A2A replies only ever reach the transcript inside {@code ASSISTANT}
 * rows, so this is what stops text the user did not write from becoming a memory. A memory id must
 * be one of the owner's live memories, which is what stops the model reaching another user's data:
 * no other user's memory is ever in the context to be named.
 */
@Component
public class ReflectionValidator {
    private static final int STEM_LENGTH = 4;
    private static final int MIN_WORD_LENGTH = 3;
    private static final Pattern WORD_SEPARATOR = Pattern.compile("[^\\p{L}\\p{N}]+");

    private static final Set<String> FILLER_WORDS = Set.of(
            "the", "and", "you", "your", "for", "are", "was", "but", "not", "has", "had", "his", "her", "its",
            "our", "can", "any", "all", "get", "got", "one", "use", "uses", "user", "users", "that", "this",
            "with", "from", "they", "them", "their", "have", "what", "when", "would", "about", "always",
            "never", "should", "prefer", "prefers", "wants", "likes", "does");

    private final int maxMemoryCharacters;
    private final double minConfidence;

    public ReflectionValidator(@Value("${solesonic.llm.memory.max-memory-characters}") int maxMemoryCharacters,
                               @Value("${solesonic.llm.memory.min-confidence}") double minConfidence) {
        this.maxMemoryCharacters = maxMemoryCharacters;
        this.minConfidence = minConfidence;
    }

    public ValidatedReflection validate(ReflectionContext context, ReflectionResult result) {
        return new Validation(context).run(result);
    }

    private final class Validation {
        private final ReflectionContext context;
        private final Map<UUID, ChatMessage> userMessagesById;
        private final Map<UUID, UserMemory> memoriesById;
        private final Map<UUID, ZonedDateTime> newestEvidenceByMemoryId = new HashMap<>();
        private final Map<UUID, Set<UUID>> evidenceChatsByMemoryId = new HashMap<>();
        private final Set<UUID> targeted = new HashSet<>();
        private final Map<DropReason, Integer> drops = new EnumMap<>(DropReason.class);
        private final List<Addition> additions = new ArrayList<>();
        private final List<Supersession> supersessions = new ArrayList<>();
        private final List<Confirmation> confirmations = new ArrayList<>();

        private Validation(ReflectionContext context) {
            this.context = context;

            this.userMessagesById = context.window().stream()
                    .filter(message -> message.getMessageType() == MessageType.USER)
                    .filter(message -> context.chatId().equals(message.getChatId()))
                    .collect(Collectors.toMap(ChatMessage::getId, Function.identity(), (first, _) -> first, LinkedHashMap::new));

            this.memoriesById = context.memories().stream()
                    .filter(memory -> context.userId().equals(memory.getUserId()))
                    .filter(memory -> UserMemoryService.LIVE_STATUSES.contains(memory.getStatus()))
                    .collect(Collectors.toMap(UserMemory::getId, Function.identity()));

            for (UserMemoryEvidence evidence : context.evidence()) {
                if (!context.userId().equals(evidence.getUserId())) {
                    continue;
                }

                newestEvidenceByMemoryId.merge(evidence.getMemoryId(), evidence.getChatMessageTimestamp(),
                        (first, second) -> first.isAfter(second) ? first : second);
                evidenceChatsByMemoryId.computeIfAbsent(evidence.getMemoryId(), _ -> new HashSet<>()).add(evidence.getChatId());
            }
        }

        private ValidatedReflection run(ReflectionResult result) {
            for (MemorySupersession supersession : listOrEmpty(result.supersede())) {
                supersede(supersession.memoryId(), supersession.content(), supersession.confidence(), supersession.evidenceMessageIds());
            }

            for (MemoryAddition addition : listOrEmpty(result.add())) {
                add(addition);
            }

            for (MemoryConfirmation confirmation : listOrEmpty(result.confirm())) {
                confirm(confirmation);
            }

            return new ValidatedReflection(List.copyOf(additions), List.copyOf(supersessions), List.copyOf(confirmations), Map.copyOf(drops));
        }

        private void add(MemoryAddition addition) {
            if (addition.kind() == null) {
                drop(DropReason.MISSING_KIND);
                return;
            }

            String key = UserMemoryService.normalizeKey(addition.key());

            Optional<UserMemory> keyHolder = key == null ? Optional.empty() : memoriesById.values().stream()
                    .filter(memory -> key.equals(memory.getMemoryKey()))
                    .min(Comparator.comparingInt((UserMemory memory) -> memory.getStatus() == MemoryStatus.ACTIVE ? 0 : 1));

            if (keyHolder.isPresent()) {
                UserMemory holder = keyHolder.get();

                if (pendingFromAnotherChat(holder)) {
                    restate(holder, addition);
                } else {
                    supersede(holder.getId(), addition.content(), addition.confidence(), addition.evidenceMessageIds());
                }

                return;
            }

            String content = normalizeContent(addition.content());

            if (content == null) {
                drop(DropReason.BLANK_CONTENT);
                return;
            }

            Double confidence = acceptedConfidence(addition.confidence());

            if (confidence == null) {
                return;
            }

            List<ChatMessage> evidence = evidence(addition.evidenceMessageIds());

            if (evidence.isEmpty()) {
                return;
            }

            if (!grounded(content, evidence)) {
                drop(DropReason.UNGROUNDED);
                return;
            }

            MemoryStatus status = UserMemoryService.statusFor(addition.kind(), MemoryOrigin.INFERRED, 1);

            additions.add(new Addition(addition.kind(), key, content, confidence, status, evidence));
        }

        private void supersede(UUID memoryId, String proposedContent, double proposedConfidence, List<UUID> evidenceMessageIds) {
            UserMemory target = target(memoryId);

            if (target == null) {
                return;
            }

            String content = normalizeContent(proposedContent);

            if (content == null) {
                drop(DropReason.BLANK_CONTENT);
                return;
            }

            Double confidence = acceptedConfidence(proposedConfidence);

            if (confidence == null) {
                return;
            }

            List<ChatMessage> evidence = evidence(evidenceMessageIds);

            if (evidence.isEmpty()) {
                return;
            }

            if (!grounded(content, evidence)) {
                drop(DropReason.UNGROUNDED);
                return;
            }

            ZonedDateTime targetNewest = newestEvidenceByMemoryId.get(target.getId());
            ZonedDateTime supersessionNewest = newest(evidence);

            if (targetNewest != null && supersessionNewest.isBefore(targetNewest)) {
                drop(DropReason.OLDER_EVIDENCE);
                return;
            }

            targeted.add(target.getId());
            supersessions.add(new Supersession(target, content, confidence, target.getStatus(), evidence));
        }

        /**
         * A pending memory's key added again in a later conversation is the user saying it again, so it
         * counts towards activation as a confirmation does. As a supersession the replacement would
         * carry this conversation's evidence alone and stay pending however often it was repeated.
         */
        private void restate(UserMemory holder, MemoryAddition addition) {
            UserMemory target = target(holder.getId());

            if (target == null) {
                return;
            }

            String content = normalizeContent(addition.content());

            if (content == null) {
                drop(DropReason.BLANK_CONTENT);
                return;
            }

            if (acceptedConfidence(addition.confidence()) == null) {
                return;
            }

            List<ChatMessage> evidence = evidence(addition.evidenceMessageIds());

            if (evidence.isEmpty()) {
                return;
            }

            if (!grounded(content, evidence)) {
                drop(DropReason.UNGROUNDED);
                return;
            }

            confirm(target, evidence);
        }

        private boolean pendingFromAnotherChat(UserMemory memory) {
            return memory.getStatus() == MemoryStatus.PENDING
                    && evidenceChatsByMemoryId.getOrDefault(memory.getId(), Set.of())
                    .stream()
                    .anyMatch(chatId -> !context.chatId().equals(chatId));
        }

        private void confirm(MemoryConfirmation confirmation) {
            UserMemory target = target(confirmation.memoryId());

            if (target == null) {
                return;
            }

            List<ChatMessage> evidence = evidence(confirmation.evidenceMessageIds());

            if (evidence.isEmpty()) {
                return;
            }

            confirm(target, evidence);
        }

        private void confirm(UserMemory target, List<ChatMessage> evidence) {
            Set<UUID> chats = new HashSet<>(evidenceChatsByMemoryId.getOrDefault(target.getId(), Set.of()));
            chats.add(context.chatId());

            boolean activate = target.getStatus() == MemoryStatus.PENDING
                    && UserMemoryService.statusFor(target.getKind(), target.getOrigin(), chats.size()) == MemoryStatus.ACTIVE;

            targeted.add(target.getId());
            confirmations.add(new Confirmation(target, evidence, activate));
        }

        private UserMemory target(UUID memoryId) {
            UserMemory target = memoryId == null ? null : memoriesById.get(memoryId);

            if (target == null) {
                drop(DropReason.UNKNOWN_MEMORY);
                return null;
            }

            if (target.getOrigin().userSet()) {
                drop(DropReason.USER_SET_MEMORY);
                return null;
            }

            if (targeted.contains(target.getId())) {
                drop(DropReason.DUPLICATE_TARGET);
                return null;
            }

            return target;
        }

        private List<ChatMessage> evidence(List<UUID> evidenceMessageIds) {
            List<ChatMessage> evidence = new ArrayList<>();

            for (UUID evidenceMessageId : listOrEmpty(evidenceMessageIds)) {
                ChatMessage message = evidenceMessageId == null ? null : userMessagesById.get(evidenceMessageId);

                if (message == null) {
                    drop(DropReason.INVALID_EVIDENCE);
                    continue;
                }

                if (!evidence.contains(message)) {
                    evidence.add(message);
                }
            }

            if (evidence.isEmpty()) {
                drop(DropReason.NO_EVIDENCE);
            }

            return evidence;
        }

        private Double acceptedConfidence(double proposed) {
            double clamped = Double.isNaN(proposed) ? 0.0 : Math.clamp(proposed, 0.0, 1.0);

            if (clamped < minConfidence) {
                drop(DropReason.LOW_CONFIDENCE);
                return null;
            }

            return clamped;
        }

        private void drop(DropReason reason) {
            drops.merge(reason, 1, Integer::sum);
        }
    }

    private String normalizeContent(String content) {
        if (StringUtils.isBlank(content)) {
            return null;
        }

        return StringUtils.truncate(content.trim(), maxMemoryCharacters);
    }

    /**
     * Whether the proposed text shares at least one content word with what the user actually wrote.
     * <p>
     * Citing a {@code USER} message proves only that the message exists, not that the memory came from
     * it: the model could lift a line from a summarised email and attach any real message id to it.
     * Requiring the words to overlap closes that, at the cost of missing a memory the user only
     * implied — a missed memory is cheaper than a planted one. Words are compared by their first
     * {@value #STEM_LENGTH} characters so "moved" and "moving" still match.
     */
    static boolean grounded(String content, List<ChatMessage> evidence) {
        Set<String> contentStems = stems(content);

        if (contentStems.isEmpty()) {
            return false;
        }

        for (ChatMessage message : evidence) {
            if (!Collections.disjoint(contentStems, stems(Objects.toString(message.getMessage(), "")))) {
                return true;
            }
        }

        return false;
    }

    private static Set<String> stems(String text) {
        return WORD_SEPARATOR.splitAsStream(text.toLowerCase(Locale.ROOT))
                .filter(word -> word.length() >= MIN_WORD_LENGTH)
                .filter(word -> !FILLER_WORDS.contains(word))
                .map(word -> word.substring(0, Math.min(STEM_LENGTH, word.length())))
                .collect(Collectors.toSet());
    }

    private static ZonedDateTime newest(List<ChatMessage> evidence) {
        return evidence.stream()
                .map(ChatMessage::getTimestamp)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElseThrow();
    }

    private static <T> List<T> listOrEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
