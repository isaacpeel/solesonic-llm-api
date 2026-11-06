package com.solesonic.repository.ingestion;


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
import org.springframework.data.domain.PageRequest;
import com.solesonic.model.ingestion.DocumentStatus;
import com.solesonic.model.ingestion.IngestedDocument;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The document manifest of a shared conversation, against a real Postgres built by the full Flyway
 * history: every completed document with a RETRIEVE grant to the chat, whoever uploaded it, and no other
 * chat's. The rule is a query, which neither the compiler nor startup checks.
 * <p>
 * Skipped where Docker is unavailable.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class ChatDocumentManifestRepositoryTest {

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
    private IngestedDocumentRepository ingestedDocumentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final UUID chatId = UUID.randomUUID();
    private final UUID otherChatId = UUID.randomUUID();

    private UUID document(String fileName, String principalType, String principalId, String grantKind, String status) {
        UUID documentId = UUID.randomUUID();
        jdbcTemplate.update("insert into public.ingested_document (id, file_name, document_source, created) values (?, ?, 'CHAT', now())",
                documentId, fileName);
        jdbcTemplate.update("""
                insert into public.document_entitlement (id, ingested_document_id, principal_type, principal_id, grant_kind, granted_at)
                values (?, ?, ?, ?, ?, now())
                """, UUID.randomUUID(), documentId, principalType, principalId, grantKind);

        if (status != null) {
            jdbcTemplate.update("insert into public.status_history (id, document_id, document_status, timestamp) values (?, ?, ?, now())",
                    UUID.randomUUID(), documentId, status);
        }

        return documentId;
    }

    private List<String> manifest(UUID forChatId) {
        return ingestedDocumentRepository.findAllChatDocuments(forChatId.toString(), DocumentStatus.COMPLETED, PageRequest.of(0, 20))
                .getContent().stream().map(IngestedDocument::getFileName).toList();
    }

    @Test
    void test_every_completed_document_of_the_chat_is_listed_whoever_uploaded_it() {
        document("alice.pdf", "CHAT", chatId.toString(), "RETRIEVE", "COMPLETED");
        document("bob.pdf", "CHAT", chatId.toString(), "RETRIEVE", "COMPLETED");

        assertThat(manifest(chatId)).containsExactlyInAnyOrder("alice.pdf", "bob.pdf");
    }

    @Test
    void test_another_chats_documents_are_not_listed() {
        document("mine.pdf", "CHAT", chatId.toString(), "RETRIEVE", "COMPLETED");
        document("theirs.pdf", "CHAT", otherChatId.toString(), "RETRIEVE", "COMPLETED");

        assertThat(manifest(chatId)).containsExactly("mine.pdf");
    }

    @Test
    void test_a_document_that_is_not_completed_is_not_listed() {
        document("busy.pdf", "CHAT", chatId.toString(), "RETRIEVE", "PREPARING");
        document("failed.pdf", "CHAT", chatId.toString(), "RETRIEVE", "FAILED");
        document("none.pdf", "CHAT", chatId.toString(), "RETRIEVE", null);

        assertThat(manifest(chatId)).isEmpty();
    }

    @Test
    void test_a_user_or_global_document_is_not_a_chat_document() {
        document("library.pdf", "USER", chatId.toString(), "RETRIEVE", "COMPLETED");
        document("global.pdf", "GLOBAL", "global", "RETRIEVE", "COMPLETED");
        document("managed.pdf", "CHAT", chatId.toString(), "MANAGE", "COMPLETED");

        assertThat(manifest(chatId)).isEmpty();
    }

    @Test
    void test_the_page_size_bounds_the_manifest() {
        for (int document = 0; document < 5; document++) {
            document("doc" + document + ".pdf", "CHAT", chatId.toString(), "RETRIEVE", "COMPLETED");
        }

        assertThat(ingestedDocumentRepository.findAllChatDocuments(chatId.toString(), DocumentStatus.COMPLETED, PageRequest.of(0, 3))
                .getContent()).hasSize(3);
    }
}
