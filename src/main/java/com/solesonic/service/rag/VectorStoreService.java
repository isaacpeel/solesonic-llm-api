package com.solesonic.service.rag;

import com.solesonic.model.VectorSearch;
import com.solesonic.model.rag.DocumentPrincipal;
import com.solesonic.model.rag.RetrievalMetadata;
import com.solesonic.model.ingestion.VectorDocument;
import com.solesonic.model.llm.LlmApiKey;
import com.solesonic.model.user.UserPreferences;
import com.solesonic.model.ingestion.DocumentStatus;
import com.solesonic.model.ingestion.IngestedDocument;
import com.solesonic.repository.ingestion.IngestedDocumentRepository;
import com.solesonic.repository.rag.VectorStoreRepository;
import com.solesonic.service.llm.LlmCredentialScope;
import com.solesonic.service.user.UserPreferencesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.solesonic.config.openai.RagTaskOpenAiConfig.RAG_TASK_CHAT_MODEL;
import static com.solesonic.model.rag.RetrievalMetadata.ENTITLEMENTS;

@Service
public class VectorStoreService {
    private static final Logger log = LoggerFactory.getLogger(VectorStoreService.class);

    private static final int RETRIEVAL_TOP_K = 15;
    private static final int RERANK_TOP_N = 5;
    private static final int EXPANSION_QUERIES = 3;

    /**
     * How many of a conversation's documents are named to the model. A guard on prompt size; a chat
     * with more than this many documents is rare, and naming most of them is what matters.
     */
    private static final int MANIFEST_DOCUMENTS = 20;

    private final VectorStore vectorStore;
    private final VectorStoreRepository vectorStoreRepository;
    private final UserPreferencesService userPreferencesService;
    private final OpenAiChatModel taskChatModel;

    /**
     * The repository, not {@code IngestedDocumentService}: that service depends on this one.
     */
    private final IngestedDocumentRepository ingestedDocumentRepository;

    @Value("${solesonic.llm.retrieval.similarity-threshold.chat}")
    private Double defaultChatSimilarityThreshold;

    @Value("${solesonic.llm.retrieval.similarity-threshold.user}")
    private Double defaultUserSimilarityThreshold;

    @Value("${solesonic.llm.retrieval.similarity-threshold.global}")
    private Double defaultGlobalSimilarityThreshold;

    @Value("${solesonic.llm.embedding.max-query-chars}")
    private int maxQueryChars;

    /**
     * Folds a follow-up and the conversation into a standalone query before retrieval, for chats with
     * documents of their own. One extra task-model call per such turn; off until evaluated.
     */
    @Value("${solesonic.llm.retrieval.query-compression.enabled:false}")
    private boolean queryCompressionEnabled;

    /**
     * When the conversation tier finds nothing, how many of its chunks to take anyway, below the
     * threshold. Zero is off; see {@link ScopedDocumentRetriever.ScopedTier}.
     */
    @Value("${solesonic.llm.retrieval.chat-floor-top-k:0}")
    private int chatFloorTopK;

    public VectorStoreService(VectorStore vectorStore,
                              VectorStoreRepository vectorStoreRepository,
                              UserPreferencesService userPreferencesService,
                              @Qualifier(RAG_TASK_CHAT_MODEL) OpenAiChatModel taskChatModel,
                              IngestedDocumentRepository ingestedDocumentRepository) {
        this.vectorStore = vectorStore;
        this.vectorStoreRepository = vectorStoreRepository;
        this.userPreferencesService = userPreferencesService;
        this.taskChatModel = taskChatModel;
        this.ingestedDocumentRepository = ingestedDocumentRepository;
    }

    /**
     * Builds the per-request RAG advisor, retrieving at conversation, user and global scope in that
     * order of precedence.
     * <p>
     * {@code chatId} may be null for a call that belongs to no conversation, in which case the
     * conversation tier is simply absent rather than filtered to nothing.
     * <p>
     * Every model call the advisor makes carries {@code llmApiKey}: the task clients as a request
     * header, since the advisor runs on a thread of its own, and the retriever's embeddings through
     * the scope it binds around each search.
     * <p>
     * Everything that makes a conversation's own documents easier to reach — follow-up compression,
     * the chat-tier floor, the document manifest in {@link ChatDocumentQueryAugmenter} — applies only
     * when the conversation has retrievable documents, so a chat without any behaves, and costs,
     * exactly as before. Compression and the floor are also off unless configured.
     * <p>
     * Only the default route and MCP prompt commands build this advisor. Tool, local-tool, agent and
     * sticky-agent routes do not retrieve at all; whether they should is an open product question.
     */
    @SuppressWarnings("unused")
    public Advisor retrievalAugmentationAdvisor(UUID userId, UUID chatId, LlmApiKey llmApiKey) {
        UserPreferences userPreferences = userPreferencesService.get(userId);

        List<String> chatDocumentNames = chatDocumentNames(userId, chatId);
        boolean hasChatDocuments = !chatDocumentNames.isEmpty();

        HistoryAwareQueryCompressor historyAwareQueryCompressor = new HistoryAwareQueryCompressor(
                queryCompressionEnabled,
                hasChatDocuments,
                () -> CompressionQueryTransformer.builder()
                        .chatClientBuilder(taskClientBuilder(llmApiKey))
                        .build());

        // Deferred: query expansion and reranking stay off until they are evaluated.
        MultiQueryExpander multiQueryExpander = MultiQueryExpander.builder()
                .chatClientBuilder(taskClientBuilder(llmApiKey))
                .numberOfQueries(EXPANSION_QUERIES)
                .build();

        LlmDocumentReranker documentReranker = new LlmDocumentReranker(taskClientBuilder(llmApiKey).build(), RERANK_TOP_N);

        return advisor(llmApiKey, tiers(userId, chatId, userPreferences, hasChatDocuments ? chatFloorTopK : 0),
                historyAwareQueryCompressor, chatDocumentNames);
    }

    /**
     * The advisor for a shared conversation, which every participant reads: the global corpus tier
     * only. Not the sender's own tier, and not a conversation tier either, because a shared chat has
     * no attachments of its own. With no conversation documents there is no manifest and no
     * compression, and the threshold is the configured default, not any one participant's preference.
     */
    public Advisor sharedRetrievalAugmentationAdvisor(UUID chatId, LlmApiKey llmApiKey) {
        HistoryAwareQueryCompressor historyAwareQueryCompressor = new HistoryAwareQueryCompressor(
                queryCompressionEnabled,
                false,
                () -> CompressionQueryTransformer.builder()
                        .chatClientBuilder(taskClientBuilder(llmApiKey))
                        .build());

        log.debug("Retrieving at global scope only for shared chat {}", chatId);

        return advisor(llmApiKey, List.of(tier(DocumentPrincipal.global(), defaultGlobalSimilarityThreshold)),
                historyAwareQueryCompressor, List.of());
    }

    private Advisor advisor(LlmApiKey llmApiKey,
                            List<ScopedDocumentRetriever.ScopedTier> tiers,
                            HistoryAwareQueryCompressor historyAwareQueryCompressor,
                            List<String> chatDocumentNames) {
        ScopedDocumentRetriever scopedDocumentRetriever = new ScopedDocumentRetriever(vectorStore, RETRIEVAL_TOP_K, tiers, llmApiKey);

        return RetrievalAugmentationAdvisor.builder()
                .queryTransformers(historyAwareQueryCompressor, new TruncatingQueryTransformer(maxQueryChars))
                .documentRetriever(scopedDocumentRetriever)
                .documentPostProcessors(new RetrievalLoggingPostProcessor())
                .queryAugmenter(new ChatDocumentQueryAugmenter(chatDocumentNames))
                .build();
    }

    /**
     * The names of the documents this conversation can retrieve from: completed, carrying a
     * {@code RETRIEVE} grant to this chat. Derived from grants rather than from attachment chunk
     * counts, so a promoted or removed document is excluded and one uploaded straight to the chat is
     * included.
     * <p>
     * Retrieval must never fail a turn, so a failed lookup is treated as "no documents" — which is
     * exactly the previous behaviour.
     */
    private List<String> chatDocumentNames(UUID userId, UUID chatId) {
        if (chatId == null) {
            return List.of();
        }

        try {
            return ingestedDocumentRepository.findAllChatDocumentsManagedBy(userId.toString(), chatId.toString(),
                            DocumentStatus.COMPLETED, PageRequest.of(0, MANIFEST_DOCUMENTS))
                    .getContent().stream()
                    .map(IngestedDocument::getFileName)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
        } catch (RuntimeException runtimeException) {
            log.warn("Could not list the documents of chat {}; retrieving without them: {}",
                    chatId, runtimeException.getMessage());

            return List.of();
        }
    }

    private ChatClient.Builder taskClientBuilder(LlmApiKey llmApiKey) {
        return ChatClient.builder(taskChatModel)
                .defaultOptions(OpenAiChatOptions.builder().customHeaders(llmApiKey.authorizationHeaders()));
    }

    /**
     * The scope tiers to search, most specific first.
     * <p>
     * Built with {@link FilterExpressionBuilder} against the metadata keys every ingestion path
     * stamps, which pgvector converts to a JSON path predicate over the {@code metadata} column.
     * The ids are compared as strings because that is how they are written — a UUID serialized into
     * JSON is a string, and a filter comparing against anything else matches nothing.
     */
    private List<ScopedDocumentRetriever.ScopedTier> tiers(UUID userId,
                                                           UUID chatId,
                                                           UserPreferences userPreferences,
                                                           int chatFloor) {
        List<ScopedDocumentRetriever.ScopedTier> tiers = new ArrayList<>(3);

        if (chatId != null) {
            Double chatSimilarityThreshold = Optional.ofNullable(userPreferences.getChatSimilarityThreshold())
                    .orElse(defaultChatSimilarityThreshold);

            ScopedDocumentRetriever.ScopedTier chatTier = tier(DocumentPrincipal.chat(chatId), chatSimilarityThreshold);

            tiers.add(new ScopedDocumentRetriever.ScopedTier(chatTier.principal(), chatTier.filterExpression(),
                    chatTier.similarityThreshold(), chatFloor));
        }

        Double userSimilarityThreshold = Optional.ofNullable(userPreferences.getUserSimilarityThreshold())
                .orElse(defaultUserSimilarityThreshold);

        tiers.add(tier(DocumentPrincipal.user(userId), userSimilarityThreshold));

        Double globalSimilarityThreshold = Optional.ofNullable(userPreferences.getGlobalSimilarityThreshold())
                .orElse(defaultGlobalSimilarityThreshold);

        tiers.add(tier(DocumentPrincipal.global(), globalSimilarityThreshold));

        return tiers;
    }

    /**
     * One principal's tier: a single equality against {@link RetrievalMetadata#ENTITLEMENTS}.
     * <p>
     * A single {@code eq} rather than the two-key {@code and} each tier used to need, because a
     * chunk now names its audiences directly instead of carrying a scope plus whichever id that
     * scope implied. It still matches a chunk granted to several principals: pgvector renders this
     * as a jsonpath predicate and Postgres evaluates {@code @@} in lax mode, which unwraps the array
     * before comparing.
     */
    private static ScopedDocumentRetriever.ScopedTier tier(DocumentPrincipal principal, Double similarityThreshold) {
        Filter.Expression filterExpression = new FilterExpressionBuilder()
                .eq(ENTITLEMENTS, principal.key())
                .build();

        return new ScopedDocumentRetriever.ScopedTier(principal, filterExpression, similarityThreshold);
    }

    public void save(List<Document> documents) {
        try {
            vectorStore.accept(documents);
        } catch (Exception e) {
            log.error("Error saving vector", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * The query is embedded on {@code llmApiKey}, the caller's own key.
     */
    public List<Document> findSimilarDocuments(VectorSearch vectorSearch, LlmApiKey llmApiKey) {
        SearchRequest searchRequest = SearchRequest.builder()
                .query(vectorSearch.query())
                .similarityThreshold(vectorSearch.similarityThreshold())
                .topK(vectorSearch.topK())
                .build();

        return LlmCredentialScope.asUser(llmApiKey, () -> vectorStore.similaritySearch(searchRequest));
    }

    public List<VectorDocument> findByIngestedDocumentId(UUID ingestedDocumentId) {
        return vectorStoreRepository.findByIngestedDocumentId(ingestedDocumentId.toString())
                .orElse(Collections.emptyList());
    }

    public boolean hasChunks(UUID ingestedDocumentId) {
        return vectorStoreRepository.existsChunkForDocument(ingestedDocumentId.toString());
    }

    public void delete(List<VectorDocument> vectorDocuments) {
        vectorStoreRepository.deleteAll(vectorDocuments);
    }

    public void delete(UUID ingestedDocumentId) {
        vectorStoreRepository.deleteById(ingestedDocumentId);
    }

    /**
     * Re-points every chunk of one document at a new audience, clearing the chat provenance that
     * would otherwise take it with the conversation it came from.
     * <p>
     * The keys are rendered to a JSON array here rather than by the caller, so the one place that
     * produces an entitlement key ({@code DocumentEntitlementService.retrievalKeys}) stays the one
     * place, and the array a chunk carries is written the same way whether it was stamped at
     * ingestion or rewritten by a promotion.
     */
    @Transactional
    public void promoteChunks(UUID ingestedDocumentId, List<String> entitlements) {
        if (entitlements.isEmpty()) {
            throw new IllegalArgumentException(
                    "Refusing to rewrite chunks of " + ingestedDocumentId + " to no entitlements at all");
        }

        String entitlementsJson = entitlements.stream()
                .map(entitlement -> "\"" + entitlement.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",", "[", "]"));

        int updated = vectorStoreRepository.promoteChunks(ingestedDocumentId.toString(), entitlementsJson);

        log.info("Re-pointed {} chunk(s) of document {} at {}", updated, ingestedDocumentId, entitlements);
    }

    /**
     * Discards every chunk that came from one chat. Joins the caller's transaction so that a
     * conversation and the documents that were attached to it go together or not at all.
     */
    @Transactional
    public void deleteByChatId(UUID chatId) {
        int deleted = vectorStoreRepository.deleteByChatId(chatId.toString());

        if (deleted > 0) {
            log.info("Deleted {} vector store chunk(s) of chat {}", deleted, chatId);
        }
    }

    /**
     * Discards the chunks of one attachment, for a user removing a single document from a
     * conversation they are keeping.
     */
    @Transactional
    public void deleteByChatAttachmentId(UUID chatAttachmentId) {
        int deleted = vectorStoreRepository.deleteByChatAttachmentId(chatAttachmentId.toString());

        if (deleted > 0) {
            log.info("Deleted {} vector store chunk(s) of attachment {}", deleted, chatAttachmentId);
        }
    }
}
