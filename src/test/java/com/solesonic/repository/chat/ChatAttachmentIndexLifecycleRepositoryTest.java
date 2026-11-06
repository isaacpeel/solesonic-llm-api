package com.solesonic.repository.chat;

import com.solesonic.model.chat.attachment.ChatAttachment;
import com.solesonic.model.chat.attachment.ChatAttachmentIndexState;
import com.solesonic.model.ingestion.IngestedDocument;
import com.solesonic.repository.ingestion.IngestedDocumentRepository;
import com.solesonic.repository.rag.VectorStoreRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lifecycle columns and queries against a real Postgres built by the full Flyway history,
 * {@code V3_44} included.
 * <p>
 * {@code ddl-auto=none} means a missing entity mapping would not fail startup, it would silently
 * ignore the column — so the mapping is proven here by round-tripping every column. The lease is a
 * conditional update, so its one-winner guarantee is proven with real transactions racing.
 * <p>
 * Not {@code @Transactional}: each repository call commits on its own, as it does in production,
 * which is what the race needs. Skipped where Docker is unavailable.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class ChatAttachmentIndexLifecycleRepositoryTest {

    @Container
    @SuppressWarnings("resource")
    private static final GenericContainer<?> POSTGRES = new GenericContainer<>("pgvector/pgvector:0.8.0-pg17")
            .withEnv("POSTGRES_USER", "test")
            .withEnv("POSTGRES_PASSWORD", "test")
            .withEnv("POSTGRES_DB", "test")
            .withExposedPorts(5432)
            // What the database is provisioned with outside Flyway (see src/test/resources/init_schema.sql).
            .withCopyToContainer(Transferable.of("""
                    create extension if not exists "uuid-ossp";
                    create extension if not exists vector;
                    create extension if not exists hstore;
                    """), "/docker-entrypoint-initdb.d/00-extensions.sql")
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:postgresql://%s:%d/test".formatted(POSTGRES.getHost(), POSTGRES.getMappedPort(5432)));
        registry.add("spring.datasource.username", () -> "test");
        registry.add("spring.datasource.password", () -> "test");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.placeholders.DB_OWNER", () -> "test");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    /**
     * The slice's own configuration, used instead of {@code SolesonicLlmAPI}: the application class
     * carries {@code @EnableRedisRepositories} and friends, which would drag Redis into a JPA test.
     * The JPA attribute converters for encrypted columns still need the two beans below; nothing
     * here touches those columns.
     */
    @SpringBootConfiguration
    @EntityScan(basePackages = "com.solesonic.model")
    @EnableJpaRepositories(basePackages = "com.solesonic.repository")
    static class JpaSliceConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        BytesEncryptor bytesEncryptor() {
            return new BytesEncryptor() {
                @Override
                public byte[] encrypt(byte[] bytes) {
                    return bytes;
                }

                @Override
                public byte[] decrypt(byte[] bytes) {
                    return bytes;
                }
            };
        }
    }

    @Autowired
    private ChatAttachmentRepository chatAttachmentRepository;

    @Autowired
    private VectorStoreRepository vectorStoreRepository;

    @Autowired
    private IngestedDocumentRepository ingestedDocumentRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ChatAttachment saved() {
        ChatAttachment chatAttachment = new ChatAttachment();
        chatAttachment.setUserId(UUID.randomUUID());
        chatAttachment.setFileName("notes.pdf");
        chatAttachment.setContentType("application/pdf");
        chatAttachment.setFileData("hello".getBytes());
        chatAttachment.setFileSizeBytes(5);
        chatAttachment.setCreated(ZonedDateTime.now());
        chatAttachment.setChunkCount(3);

        return chatAttachmentRepository.save(chatAttachment);
    }

    private <T> T inTransaction(Callable<T> work) {
        return new TransactionTemplate(transactionManager).execute(_ -> {
            try {
                return work.call();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    /**
     * Claims against whatever attempt is currently stored, as a worker that just read the row would.
     * Truncated to the column's precision, as the service does, so the token matches.
     */
    private int claim(UUID attachmentId, ZonedDateTime now) {
        ZonedDateTime token = now.truncatedTo(ChronoUnit.MICROS);
        ZonedDateTime observed = chatAttachmentRepository.findIndexState(attachmentId).orElseThrow().indexAttemptedAt();

        return inTransaction(() -> observed == null
                ? chatAttachmentRepository.claimIndexingNeverAttempted(attachmentId, token, token.minusMinutes(15))
                : chatAttachmentRepository.claimIndexing(attachmentId, token, token.minusMinutes(15), observed));
    }

    @Test
    void test_new_columns_round_trip_through_the_entity_and_the_projection() {
        ChatAttachment chatAttachment = saved();

        ChatAttachmentIndexState fresh = chatAttachmentRepository.findIndexState(chatAttachment.getId()).orElseThrow();

        assertThat(fresh.indexDetached()).isFalse();
        assertThat(fresh.indexClaimedAt()).isNull();
        assertThat(fresh.indexAttemptedAt()).isNull();
        assertThat(fresh.chunkCount()).isEqualTo(3);

        ZonedDateTime claimedAt = ZonedDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        chatAttachment.setIndexDetached(true);
        chatAttachment.setIndexClaimedAt(claimedAt);
        chatAttachment.setIndexAttemptedAt(claimedAt);
        chatAttachmentRepository.save(chatAttachment);

        ChatAttachment reloaded = chatAttachmentRepository.findById(chatAttachment.getId()).orElseThrow();

        assertThat(reloaded.isIndexDetached()).isTrue();
        assertThat(reloaded.getIndexClaimedAt().toInstant()).isEqualTo(claimedAt.toInstant());
        assertThat(reloaded.getIndexAttemptedAt().toInstant()).isEqualTo(claimedAt.toInstant());
    }

    @Test
    void test_claim_takes_the_lease_clears_the_count_and_blocks_a_second_claim() {
        ChatAttachment chatAttachment = saved();
        ZonedDateTime now = ZonedDateTime.now();

        assertThat(claim(chatAttachment.getId(), now)).isEqualTo(1);
        assertThat(claim(chatAttachment.getId(), now)).isZero();

        ChatAttachmentIndexState claimed = chatAttachmentRepository.findIndexState(chatAttachment.getId()).orElseThrow();
        assertThat(claimed.chunkCount()).isNull();
        assertThat(claimed.indexClaimedAt()).isNotNull();
        assertThat(claimed.indexAttemptedAt()).isNotNull();

        ZonedDateTime token = claimed.indexClaimedAt();
        inTransaction(() -> chatAttachmentRepository.releaseIndexing(chatAttachment.getId(), token));

        ChatAttachmentIndexState released = chatAttachmentRepository.findIndexState(chatAttachment.getId()).orElseThrow();
        assertThat(released.indexClaimedAt()).isNull();
        assertThat(released.indexAttemptedAt()).isNotNull();

        assertThat(claim(chatAttachment.getId(), now)).isEqualTo(1);
    }

    @Test
    void test_only_one_of_many_concurrent_claims_wins() throws Exception {
        ChatAttachment chatAttachment = saved();
        ZonedDateTime now = ZonedDateTime.now();

        int workers = 8;
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executorService = Executors.newFixedThreadPool(workers)) {
            List<Future<Integer>> claims = new ArrayList<>();

            for (int worker = 0; worker < workers; worker++) {
                claims.add(executorService.submit(() -> {
                    start.await();

                    return claim(chatAttachment.getId(), now);
                }));
            }

            start.countDown();

            int winners = 0;

            for (Future<Integer> claim : claims) {
                winners += claim.get();
            }

            assertThat(winners).isEqualTo(1);
        }
    }

    @Test
    void test_a_stale_lease_can_be_taken_over() {
        ChatAttachment chatAttachment = saved();

        claim(chatAttachment.getId(), ZonedDateTime.now().minusHours(2));

        assertThat(claim(chatAttachment.getId(), ZonedDateTime.now())).isEqualTo(1);
    }

    /**
     * Review finding M3: a claim based on a stale observation fails. Another worker claimed (and
     * released) since this one looked, which moved {@code indexAttemptedAt}.
     */
    @Test
    void test_a_claim_based_on_a_stale_observation_fails() {
        ChatAttachment chatAttachment = saved();
        ZonedDateTime token = ZonedDateTime.now().truncatedTo(ChronoUnit.MICROS);

        ZonedDateTime observedBefore = chatAttachmentRepository.findIndexState(chatAttachment.getId()).orElseThrow().indexAttemptedAt();

        claim(chatAttachment.getId(), ZonedDateTime.now().minusSeconds(5));
        ChatAttachmentIndexState afterOther = chatAttachmentRepository.findIndexState(chatAttachment.getId()).orElseThrow();
        inTransaction(() -> chatAttachmentRepository.releaseIndexing(chatAttachment.getId(), afterOther.indexClaimedAt()));

        // observedBefore is null (never attempted); the other worker's claim made it non-null.
        assertThat(observedBefore).isNull();
        int stale = inTransaction(() -> chatAttachmentRepository.claimIndexingNeverAttempted(chatAttachment.getId(), token,
                token.minusMinutes(15)));

        assertThat(stale).isZero();
    }

    /**
     * Review finding M1: a worker whose lease was taken over can neither release the new holder's
     * lease nor record its own result over it.
     */
    @Test
    void test_a_superseded_worker_cannot_release_or_write_over_the_new_lease() {
        ChatAttachment chatAttachment = saved();

        claim(chatAttachment.getId(), ZonedDateTime.now().minusHours(2));
        ZonedDateTime oldToken = chatAttachmentRepository.findIndexState(chatAttachment.getId()).orElseThrow().indexClaimedAt();

        assertThat(claim(chatAttachment.getId(), ZonedDateTime.now())).isEqualTo(1);
        ZonedDateTime newToken = chatAttachmentRepository.findIndexState(chatAttachment.getId()).orElseThrow().indexClaimedAt();

        assertThat(inTransaction(() -> chatAttachmentRepository.saveChunkCountUnderLease(chatAttachment.getId(), 9, oldToken))).isZero();
        assertThat(inTransaction(() -> chatAttachmentRepository.releaseIndexing(chatAttachment.getId(), oldToken))).isZero();

        ChatAttachmentIndexState stillHeld = chatAttachmentRepository.findIndexState(chatAttachment.getId()).orElseThrow();
        assertThat(stillHeld.indexClaimedAt().toInstant()).isEqualTo(newToken.toInstant());
        assertThat(stillHeld.chunkCount()).isNull();

        assertThat(inTransaction(() -> chatAttachmentRepository.saveChunkCountUnderLease(chatAttachment.getId(), 4, newToken))).isEqualTo(1);
        assertThat(inTransaction(() -> chatAttachmentRepository.releaseIndexing(chatAttachment.getId(), newToken))).isEqualTo(1);
        assertThat(chatAttachmentRepository.findIndexState(chatAttachment.getId()).orElseThrow().chunkCount()).isEqualTo(4);
    }

    /**
     * A tombstoned attachment cannot be claimed, so a heal racing a promotion loses to it.
     */
    @Test
    void test_a_detached_attachment_cannot_be_claimed_and_keeps_its_count() {
        ChatAttachment chatAttachment = saved();

        inTransaction(() -> chatAttachmentRepository.markIndexDetached(chatAttachment.getId()));

        assertThat(claim(chatAttachment.getId(), ZonedDateTime.now())).isZero();

        ChatAttachmentIndexState detached = chatAttachmentRepository.findIndexState(chatAttachment.getId()).orElseThrow();
        assertThat(detached.indexDetached()).isTrue();
        assertThat(detached.chunkCount()).isEqualTo(3);
    }

    /**
     * {@code @DynamicUpdate}: a load-then-save of an unrelated column, begun before a tombstone
     * commits, must not write its stale {@code indexDetached=false} back over the tombstone.
     */
    @Test
    void test_saving_another_column_does_not_undo_a_concurrent_tombstone() {
        ChatAttachment chatAttachment = saved();

        TransactionTemplate independent = new TransactionTemplate(transactionManager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        inTransaction(() -> {
            ChatAttachment loaded = chatAttachmentRepository.findById(chatAttachment.getId()).orElseThrow();

            independent.execute(_ -> jdbcTemplate.update(
                    "update public.chat_attachment set index_detached = true where id = ?",
                    chatAttachment.getId()));

            loaded.setChunkCount(9);

            return chatAttachmentRepository.save(loaded);
        });

        ChatAttachmentIndexState after = chatAttachmentRepository.findIndexState(chatAttachment.getId()).orElseThrow();

        assertThat(after.chunkCount()).isEqualTo(9);
        assertThat(after.indexDetached()).isTrue();
    }

    @Test
    void test_claiming_indexed_lists_only_bound_undetached_attachments_with_a_count() {
        ChatAttachment bound = saved();
        bound.setChatId(UUID.randomUUID());
        bound.setChatMessageId(UUID.randomUUID());
        chatAttachmentRepository.save(bound);

        ChatAttachment detached = saved();
        detached.setChatId(UUID.randomUUID());
        detached.setChatMessageId(UUID.randomUUID());
        detached.setIndexDetached(true);
        chatAttachmentRepository.save(detached);

        ChatAttachment staged = saved();

        List<UUID> ids = chatAttachmentRepository.findClaimingIndexed().stream()
                .map(ChatAttachmentIndexState::id)
                .toList();

        assertThat(ids).contains(bound.getId()).doesNotContain(detached.getId(), staged.getId());
    }

    /**
     * A promoted document must survive deletion of the chat it came from. Teardown finds documents by
     * {@code CHAT_ID} provenance, which promotion strips — so the promoted row is out of its reach.
     */
    @Test
    void test_chat_teardown_does_not_reach_a_promoted_document() {
        UUID chatId = UUID.randomUUID();
        UUID stillInChat = UUID.randomUUID();
        UUID promoted = UUID.randomUUID();

        jdbcTemplate.update("insert into public.ingested_document (id, document_source, metadata) values (?, 'CHAT', ?::jsonb)",
                stillInChat, "{\"CHAT_ID\": \"" + chatId + "\", \"CHAT_ATTACHMENT_ID\": \"" + UUID.randomUUID() + "\"}");
        jdbcTemplate.update("insert into public.ingested_document (id, document_source, metadata) values (?, 'USER', ?::jsonb)",
                promoted, "{\"ORIGINAL_FILE_NAME\": \"notes.pdf\"}");

        List<UUID> reached = ingestedDocumentRepository.findByChatId(chatId.toString()).stream()
                .map(IngestedDocument::getId)
                .toList();

        assertThat(reached).containsExactly(stillInChat);
    }

    @Test
    void test_chunk_existence_is_checked_by_containment() {
        UUID ingestedDocumentId = UUID.randomUUID();

        assertThat(vectorStoreRepository.existsChunkForDocument(ingestedDocumentId.toString())).isFalse();

        jdbcTemplate.update("insert into public.vector_store (id, content, metadata) values (?, 'text', ?::json)",
                UUID.randomUUID(), "{\"INGESTED_DOCUMENT_ID\": \"" + ingestedDocumentId + "\", \"chatId\": \"x\"}");

        assertThat(vectorStoreRepository.existsChunkForDocument(ingestedDocumentId.toString())).isTrue();
        assertThat(vectorStoreRepository.existsChunkForDocument(UUID.randomUUID().toString())).isFalse();
    }
}
