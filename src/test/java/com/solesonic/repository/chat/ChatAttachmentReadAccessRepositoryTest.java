package com.solesonic.repository.chat;


import java.sql.Timestamp;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may read a chat attachment, against a real Postgres built by the full Flyway history: its uploader,
 * and a participant still seated in the conversation session that owns the attachment's chat. The rule is
 * a query, which neither the compiler nor startup checks. Also the count that tells a send its attachments
 * are the sender's own staged ones.
 * <p>
 * Skipped where Docker is unavailable.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class ChatAttachmentReadAccessRepositoryTest {

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
    private JdbcTemplate jdbcTemplate;

    private final UUID owner = UUID.randomUUID();
    private final UUID uploader = UUID.randomUUID();
    private final UUID leaver = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();

    private UUID chat() {
        UUID chatId = UUID.randomUUID();
        jdbcTemplate.update("insert into public.chat (id) values (?)", chatId);
        return chatId;
    }

    private UUID session(UUID chatId, String mode) {
        return session(chatId, mode, "ACTIVE");
    }

    private UUID session(UUID chatId, String mode, String status) {
        UUID sessionId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into public.shared_session (id, chat_id, mode, agent_name, status, created, updated)
                values (?, ?, ?, ?, ?, now(), now())
                """, sessionId, chatId, mode, "AGENT".equals(mode) ? "feud" : null, status);
        return sessionId;
    }

    private void seat(UUID sessionId, UUID userId, int seat, boolean left) {
        jdbcTemplate.update("""
                insert into public.session_participant (id, session_id, user_id, seat, name, joined, left_at)
                values (?, ?, ?, ?, ?, now(), ?)
                """, UUID.randomUUID(), sessionId, userId, seat, "user" + seat, left ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
    }

    private UUID attachment(UUID userId, UUID chatId) {
        UUID attachmentId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into public.chat_attachment (id, user_id, chat_id, chat_message_id, file_name, content_type, file_data, file_size_bytes, created)
                values (?, ?, ?, ?, 'notes.pdf', 'application/pdf', ?, 3, now())
                """, attachmentId, userId, chatId, chatId == null ? null : UUID.randomUUID(), new byte[]{1, 2, 3});
        return attachmentId;
    }

    private UUID conversationAttachment() {
        UUID chatId = chat();
        UUID sessionId = session(chatId, "CONVERSATION");
        seat(sessionId, owner, 0, false);
        seat(sessionId, uploader, 1, false);
        seat(sessionId, leaver, 2, true);

        return attachment(uploader, chatId);
    }

    @Test
    void test_the_uploader_reads_their_own_attachment() {
        UUID attachmentId = conversationAttachment();

        assertThat(chatAttachmentRepository.findReadableByIdAndUserId(attachmentId, uploader)).isPresent();
    }

    @Test
    void test_a_seated_participant_reads_an_attachment_someone_else_uploaded() {
        UUID attachmentId = conversationAttachment();

        assertThat(chatAttachmentRepository.findReadableByIdAndUserId(attachmentId, owner)).isPresent();
    }

    @Test
    void test_a_participant_who_left_cannot_read_it() {
        UUID attachmentId = conversationAttachment();

        assertThat(chatAttachmentRepository.findReadableByIdAndUserId(attachmentId, leaver)).isEmpty();
    }

    @Test
    void test_someone_outside_the_session_cannot_read_it() {
        UUID attachmentId = conversationAttachment();

        assertThat(chatAttachmentRepository.findReadableByIdAndUserId(attachmentId, stranger)).isEmpty();
    }

    @Test
    void test_a_private_chats_attachment_is_readable_only_by_its_uploader() {
        UUID attachmentId = attachment(uploader, chat());

        assertThat(chatAttachmentRepository.findReadableByIdAndUserId(attachmentId, uploader)).isPresent();
        assertThat(chatAttachmentRepository.findReadableByIdAndUserId(attachmentId, owner)).isEmpty();
    }

    @Test
    void test_a_staged_attachment_is_readable_only_by_its_uploader() {
        UUID attachmentId = attachment(uploader, null);

        assertThat(chatAttachmentRepository.findReadableByIdAndUserId(attachmentId, uploader)).isPresent();
        assertThat(chatAttachmentRepository.findReadableByIdAndUserId(attachmentId, owner)).isEmpty();
    }

    @Test
    void test_an_agent_sessions_participant_cannot_read_an_attachment_of_its_chat() {
        UUID chatId = chat();
        UUID sessionId = session(chatId, "AGENT");
        seat(sessionId, owner, 0, false);
        seat(sessionId, uploader, 1, false);

        UUID attachmentId = attachment(uploader, chatId);

        assertThat(chatAttachmentRepository.findReadableByIdAndUserId(attachmentId, owner)).isEmpty();
    }

    @Test
    void test_the_write_lookup_still_belongs_to_the_uploader_alone() {
        UUID attachmentId = conversationAttachment();

        assertThat(chatAttachmentRepository.findByIdAndUserId(attachmentId, uploader)).isPresent();
        assertThat(chatAttachmentRepository.findByIdAndUserId(attachmentId, owner)).isEmpty();
    }

    @Test
    void test_the_staged_count_counts_only_the_callers_unbound_attachments() {
        UUID staged = attachment(uploader, null);
        UUID alsoStaged = attachment(uploader, null);
        UUID bound = attachment(uploader, chat());
        UUID someoneElses = attachment(owner, null);

        assertThat(chatAttachmentRepository.countStagedByIdInAndUserId(Set.of(staged, alsoStaged), uploader)).isEqualTo(2);
        assertThat(chatAttachmentRepository.countStagedByIdInAndUserId(Set.of(staged, bound), uploader)).isEqualTo(1);
        assertThat(chatAttachmentRepository.countStagedByIdInAndUserId(Set.of(staged, someoneElses), uploader)).isEqualTo(1);
        assertThat(chatAttachmentRepository.countStagedByIdInAndUserId(Set.of(UUID.randomUUID()), uploader)).isZero();
    }

    @Test
    void test_a_participant_of_a_closed_or_expired_session_cannot_read_it() {
        for (String status : java.util.List.of("CLOSED", "EXPIRED")) {
            UUID chatId = chat();
            UUID sessionId = session(chatId, "CONVERSATION", status);
            seat(sessionId, owner, 0, false);
            seat(sessionId, uploader, 1, false);
            UUID attachmentId = attachment(uploader, chatId);

            assertThat(chatAttachmentRepository.findReadableByIdAndUserId(attachmentId, owner)).as(status).isEmpty();
            assertThat(chatAttachmentRepository.findReadableByIdAndUserId(attachmentId, uploader)).as(status + " uploader").isPresent();
        }
    }
}
