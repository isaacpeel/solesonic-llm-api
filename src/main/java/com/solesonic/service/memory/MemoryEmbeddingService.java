package com.solesonic.service.memory;

import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.repository.chat.ChatMessageRepository;
import com.solesonic.repository.memory.MemoryEmbeddingRepository;
import com.solesonic.repository.memory.MemoryEmbeddingRepository.MemorySimilarity;
import com.solesonic.repository.memory.MemoryEmbeddingRepository.StaleMemory;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps memory vectors in step with memory content, and finds the memories relevant to a chat turn.
 * <p>
 * Vectors are written off the request path: after the transaction that changed a memory commits, and
 * by {@code MemoryEmbeddingTask} for whatever that missed — a failed call, the backfill of memories
 * that predate embedding, or a change of embedding model.
 */
@Service
public class MemoryEmbeddingService {
    private static final Logger log = LoggerFactory.getLogger(MemoryEmbeddingService.class);

    private final MemoryEmbeddingRepository memoryEmbeddingRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final EmbeddingModel embeddingModel;
    private final String embeddingModelName;
    private final int batchSize;
    private final int topK;
    private final double similarityThreshold;
    private final int queryWindowMessages;
    private final int maxQueryChars;

    public MemoryEmbeddingService(MemoryEmbeddingRepository memoryEmbeddingRepository,
                                  ChatMessageRepository chatMessageRepository,
                                  EmbeddingModel embeddingModel,
                                  @Value("${spring.ai.openai.embedding.model}") String embeddingModelName,
                                  @Value("${solesonic.llm.memory.embedding.batch-size}") int batchSize,
                                  @Value("${solesonic.llm.memory.retrieval.top-k}") int topK,
                                  @Value("${solesonic.llm.memory.retrieval.similarity-threshold}") double similarityThreshold,
                                  @Value("${solesonic.llm.memory.retrieval.query-window-messages}") int queryWindowMessages,
                                  @Value("${solesonic.llm.embedding.max-query-chars}") int maxQueryChars) {
        this.memoryEmbeddingRepository = memoryEmbeddingRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.embeddingModel = embeddingModel;
        this.embeddingModelName = embeddingModelName;
        this.batchSize = batchSize;
        this.topK = topK;
        this.similarityThreshold = similarityThreshold;
        this.queryWindowMessages = queryWindowMessages;
        this.maxQueryChars = maxQueryChars;
    }

    /**
     * Embeds the oldest-changed stale memories of any user, up to {@code batch-size}.
     *
     * @return how many vectors were written
     */
    public int embedStale() {
        return embed(memoryEmbeddingRepository.findStale(embeddingModelName, batchSize));
    }

    /**
     * Embeds one user's stale memories, up to {@code batch-size}.
     *
     * @return how many vectors were written
     */
    public int embedStale(UUID userId) {
        return embed(memoryEmbeddingRepository.findStale(userId, embeddingModelName, batchSize));
    }

    /**
     * Only after commit, so the memory being embedded is visible and is not embedded for a change that
     * rolled back. A failure here is left for the task to retry.
     */
    @Async
    @TransactionalEventListener(fallbackExecution = true)
    public void onMemoriesChanged(MemoriesChangedEvent event) {
        try {
            embedStale(event.userId());
        } catch (RuntimeException exception) {
            log.warn("Could not embed memories for user {}, leaving them for the next pass ({})",
                    event.userId(), exception.getClass().getSimpleName());
        }
    }

    private int embed(List<StaleMemory> stale) {
        if (stale.isEmpty()) {
            return 0;
        }

        List<float[]> embeddings = embeddingModel.embed(stale.stream().map(StaleMemory::content).toList());

        int saved = 0;

        for (int index = 0; index < stale.size(); index++) {
            StaleMemory memory = stale.get(index);

            if (memoryEmbeddingRepository.saveEmbedding(memory.id(), memory.userId(), memory.content(), embeddings.get(index), embeddingModelName)) {
                saved++;
            }
        }

        log.info("Embedded {} of {} stale memory(ies) with {}", saved, stale.size(), embeddingModelName);

        return saved;
    }

    /**
     * The user's facts and preferences relevant to this turn, by id, with the similarity that made
     * them so — each at least {@code similarity-threshold}.
     * <p>
     * Searched twice and the better match kept: with the message alone, so a change of subject is not
     * drowned out by what came before, and with the message plus the user's previous
     * {@code query-window-messages - 1} messages, so a follow-up such as "what about tomorrow?" still
     * finds what the conversation is about. Throws when embedding or the search fails; the caller
     * decides what a turn does without them.
     */
    public Map<UUID, Double> relevant(UUID userId, UUID chatId, String message) {
        List<String> queries = queries(chatId, message);

        if (queries.isEmpty()) {
            return Map.of();
        }

        List<float[]> embeddings = embeddingModel.embed(queries);
        Map<UUID, Double> relevant = new HashMap<>();

        for (float[] embedding : embeddings) {
            for (MemorySimilarity match : memoryEmbeddingRepository.findNearest(userId, embedding, embeddingModelName, topK)) {
                if (match.similarity() >= similarityThreshold) {
                    relevant.merge(match.id(), match.similarity(), Math::max);
                }
            }
        }

        return relevant;
    }

    /**
     * This turn's message is saved as a {@code USER} row before the turn starts, so the newest row is
     * normally the message itself and is skipped; only the rows after it are earlier turns.
     */
    private List<String> queries(UUID chatId, String message) {
        List<String> queries = new ArrayList<>(2);
        String current = StringUtils.trimToEmpty(message);

        if (!current.isEmpty()) {
            queries.add(StringUtils.truncate(current, maxQueryChars));
        }

        if (chatId == null || queryWindowMessages < 2) {
            return queries;
        }

        List<String> recent = chatMessageRepository
                .findByChatIdAndMessageTypeOrderByTimestampDesc(chatId, MessageType.USER, Limit.of(queryWindowMessages))
                .stream()
                .map(ChatMessage::getMessage)
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .toList();

        int inFlight = !recent.isEmpty() && recent.getFirst().equals(current) ? 1 : 0;

        List<String> earlier = recent.stream()
                .skip(inFlight)
                .limit(queryWindowMessages - 1L)
                .toList();

        if (earlier.isEmpty()) {
            return queries;
        }

        List<String> conversation = new ArrayList<>(earlier.size() + 1);

        if (!current.isEmpty()) {
            conversation.add(current);
        }

        conversation.addAll(earlier);

        queries.add(StringUtils.truncate(String.join("\n", conversation), maxQueryChars));

        return queries;
    }
}
