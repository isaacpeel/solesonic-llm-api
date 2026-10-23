package com.solesonic.service.memory;

import com.solesonic.model.memory.ChatMemoryReflection;
import com.solesonic.model.memory.MemoryKind;
import com.solesonic.model.memory.MemoryProcessingStatus;
import com.solesonic.model.memory.MemoryRetrievalMode;
import com.solesonic.model.memory.MemoryStatus;
import com.solesonic.model.memory.MemorySummary;
import com.solesonic.model.memory.ReflectionStatus;
import com.solesonic.model.memory.ReflectionSummary;
import com.solesonic.model.memory.UserMemory;
import com.solesonic.model.memory.UserMemoryEvidence;
import com.solesonic.repository.memory.ChatMemoryReflectionRepository;
import com.solesonic.repository.memory.MemoryAdminRepository;
import com.solesonic.repository.memory.MemoryEmbeddingRepository;
import com.solesonic.repository.memory.UserMemoryAdminRepository;
import com.solesonic.repository.memory.UserMemoryEvidenceRepository;
import com.solesonic.service.user.UserProfileService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The cross-user reads behind {@code /memories} and {@code /memories}, for the
 * {@code izzy-bot-admin} group. The controllers hold the gate; nothing else may call this.
 * <p>
 * Managing one user's memories needs nothing here: an administrator uses that user's own
 * {@code /users/{userId}/memories} endpoints, under the same rules as the user.
 */
@Service
public class MemoryAdminService {
    /**
     * What a caller may sort by. Anything else is a {@code 400} rather than a query error, and the
     * id is always appended last so pages never overlap or skip rows that tie.
     */
    static final Set<String> MEMORY_SORTS = Set.of("created", "updated", "lastConfirmed", "confidence", "kind", "status", "origin", "userId");
    static final Set<String> REFLECTION_SORTS = Set.of("updated", "reflectedThrough", "attempts", "status", "userId");

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "updated");

    private final UserMemoryAdminRepository userMemoryAdminRepository;
    private final UserMemoryEvidenceRepository userMemoryEvidenceRepository;
    private final ChatMemoryReflectionRepository chatMemoryReflectionRepository;
    private final MemoryAdminRepository memoryAdminRepository;
    private final MemoryEmbeddingRepository memoryEmbeddingRepository;
    private final UserProfileService userProfileService;
    private final boolean reflectionEnabled;
    private final String memoryModel;
    private final String reflectionCron;
    private final int reflectionBatchSize;
    private final int reflectionMaxAttempts;
    private final long reflectionIdleMinutes;
    private final String embeddingModel;
    private final String embeddingCron;
    private final int embeddingBatchSize;
    private final boolean injectionEnabled;
    private final MemoryRetrievalMode retrievalMode;

    public MemoryAdminService(UserMemoryAdminRepository userMemoryAdminRepository,
                              UserMemoryEvidenceRepository userMemoryEvidenceRepository,
                              ChatMemoryReflectionRepository chatMemoryReflectionRepository,
                              MemoryAdminRepository memoryAdminRepository,
                              MemoryEmbeddingRepository memoryEmbeddingRepository,
                              UserProfileService userProfileService,
                              @Value("${solesonic.llm.memory.reflection.enabled}") boolean reflectionEnabled,
                              @Value("${solesonic.llm.memory.model}") String memoryModel,
                              @Value("${solesonic.llm.memory.reflection.cron}") String reflectionCron,
                              @Value("${solesonic.llm.memory.reflection.batch-size}") int reflectionBatchSize,
                              @Value("${solesonic.llm.memory.reflection.max-attempts}") int reflectionMaxAttempts,
                              @Value("${solesonic.llm.memory.reflection.idle-minutes}") long reflectionIdleMinutes,
                              @Value("${spring.ai.openai.embedding.model}") String embeddingModel,
                              @Value("${solesonic.llm.memory.embedding.cron}") String embeddingCron,
                              @Value("${solesonic.llm.memory.embedding.batch-size}") int embeddingBatchSize,
                              @Value("${solesonic.llm.memory.injection.enabled}") boolean injectionEnabled,
                              @Value("${solesonic.llm.memory.retrieval.mode}") MemoryRetrievalMode retrievalMode) {
        this.userMemoryAdminRepository = userMemoryAdminRepository;
        this.userMemoryEvidenceRepository = userMemoryEvidenceRepository;
        this.chatMemoryReflectionRepository = chatMemoryReflectionRepository;
        this.memoryAdminRepository = memoryAdminRepository;
        this.memoryEmbeddingRepository = memoryEmbeddingRepository;
        this.userProfileService = userProfileService;
        this.reflectionEnabled = reflectionEnabled;
        this.memoryModel = memoryModel;
        this.reflectionCron = reflectionCron;
        this.reflectionBatchSize = reflectionBatchSize;
        this.reflectionMaxAttempts = reflectionMaxAttempts;
        this.reflectionIdleMinutes = reflectionIdleMinutes;
        this.embeddingModel = embeddingModel;
        this.embeddingCron = embeddingCron;
        this.embeddingBatchSize = embeddingBatchSize;
        this.injectionEnabled = injectionEnabled;
        this.retrievalMode = retrievalMode;
    }

    /**
     * Every user's memories, newest change first unless {@code pageable} sorts otherwise. An empty
     * {@code statuses} or {@code kinds} means all of them, so superseded memories are included unless
     * filtered out.
     *
     * @param userId narrows the listing to one user; may be null
     */
    @Transactional(readOnly = true)
    public Page<MemorySummary> listMemories(UUID userId,
                                                 Collection<MemoryStatus> statuses,
                                                 Collection<MemoryKind> kinds,
                                                 Pageable pageable) {
        Set<MemoryStatus> statusFilter = orAll(statuses, MemoryStatus.class);
        Set<MemoryKind> kindFilter = orAll(kinds, MemoryKind.class);
        PageRequest pageRequest = pageRequest(pageable, MEMORY_SORTS, "id");

        Page<UserMemory> memories = userId == null
                ? userMemoryAdminRepository.findByStatusInAndKindIn(statusFilter, kindFilter, pageRequest)
                : userMemoryAdminRepository.findByUserIdAndStatusInAndKindIn(userId, statusFilter, kindFilter, pageRequest);

        Map<UUID, List<UUID>> chatIdsByMemoryId = chatIdsByMemoryId(memories.getContent());
        Map<UUID, String> usernames = userProfileService.usernames(memories.map(UserMemory::getUserId).getContent());

        return memories.map(memory -> MemorySummary.of(memory,
                usernames.get(memory.getUserId()),
                chatIdsByMemoryId.getOrDefault(memory.getId(), List.of())));
    }

    /**
     * Every conversation's reflection progress, newest change first unless {@code pageable} sorts
     * otherwise. An empty {@code statuses} means all of them.
     *
     * @param userId narrows the listing to one user; may be null
     */
    @Transactional(readOnly = true)
    public Page<ReflectionSummary> reflections(UUID userId, Collection<ReflectionStatus> statuses, Pageable pageable) {
        Set<ReflectionStatus> statusFilter = orAll(statuses, ReflectionStatus.class);
        PageRequest pageRequest = pageRequest(pageable, REFLECTION_SORTS, "chatId");

        Page<ChatMemoryReflection> reflections = userId == null
                ? chatMemoryReflectionRepository.findByStatusIn(statusFilter, pageRequest)
                : chatMemoryReflectionRepository.findByUserIdAndStatusIn(userId, statusFilter, pageRequest);

        Map<UUID, String> usernames = userProfileService.usernames(reflections.map(ChatMemoryReflection::getUserId).getContent());

        return reflections.map(reflection -> ReflectionSummary.of(reflection, usernames.get(reflection.getUserId())));
    }

    @Transactional(readOnly = true)
    public MemoryProcessingStatus status() {
        ZonedDateTime now = ZonedDateTime.now();

        MemoryProcessingStatus.Reflection reflection = new MemoryProcessingStatus.Reflection(reflectionEnabled,
                memoryModel,
                reflectionCron,
                reflectionBatchSize,
                reflectionMaxAttempts,
                reflectionIdleMinutes,
                memoryAdminRepository.countReflectionsByStatus(),
                memoryAdminRepository.countAwaitingReflection(),
                memoryAdminRepository.countUnseededChats(),
                memoryAdminRepository.countCompletedSince(now.minusHours(1)),
                memoryAdminRepository.lastCompleted());

        MemoryProcessingStatus.Embedding embedding = new MemoryProcessingStatus.Embedding(embeddingModel,
                embeddingCron,
                embeddingBatchSize,
                memoryAdminRepository.countEmbedded(embeddingModel),
                memoryEmbeddingRepository.countStale(embeddingModel));

        return new MemoryProcessingStatus(now,
                reflection,
                embedding,
                new MemoryProcessingStatus.Injection(injectionEnabled, retrievalMode),
                memoryAdminRepository.countMemoriesByStatus(),
                memoryAdminRepository.userOverviews());
    }

    static PageRequest pageRequest(Pageable pageable, Set<String> sortable, String tieBreaker) {
        for (Sort.Order order : pageable.getSort()) {
            if (!sortable.contains(order.getProperty())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Cannot sort by '" + order.getProperty() + "'; sortable: " + sortable.stream().sorted().toList());
            }
        }

        Sort sort = pageable.getSort().isSorted() ? pageable.getSort() : NEWEST_FIRST;

        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort.and(Sort.by(tieBreaker)));
    }

    /**
     * Evidence is read per owner, since {@link UserMemoryEvidenceRepository} is user-scoped like
     * everything else in the memory code.
     */
    private Map<UUID, List<UUID>> chatIdsByMemoryId(List<UserMemory> memories) {
        Map<UUID, Set<UUID>> memoryIdsByUserId = memories.stream()
                .collect(Collectors.groupingBy(UserMemory::getUserId,
                        Collectors.mapping(UserMemory::getId, Collectors.toSet())));

        return memoryIdsByUserId.entrySet()
                .stream()
                .flatMap(entry -> userMemoryEvidenceRepository.findByUserIdAndMemoryIdIn(entry.getKey(), entry.getValue()).stream())
                .collect(Collectors.groupingBy(UserMemoryEvidence::getMemoryId,
                        Collectors.collectingAndThen(
                                Collectors.mapping(UserMemoryEvidence::getChatId, Collectors.toCollection(LinkedHashSet::new)),
                                List::copyOf)));
    }

    private static <E extends Enum<E>> Set<E> orAll(Collection<E> values, Class<E> type) {
        return values == null || values.isEmpty() ? EnumSet.allOf(type) : EnumSet.copyOf(values);
    }
}
