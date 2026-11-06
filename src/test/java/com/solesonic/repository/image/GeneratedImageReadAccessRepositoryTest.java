package com.solesonic.repository.image;


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
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may read a generated image, against a real Postgres built by the full Flyway history: its owner,
 * and a participant still seated in the conversation session that owns the image's chat. The rule is a
 * query, which neither the compiler nor startup checks.
 * <p>
 * Skipped where Docker is unavailable.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class GeneratedImageReadAccessRepositoryTest {

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
    private GeneratedImageRepository generatedImageRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final UUID owner = UUID.randomUUID();
    private final UUID seated = UUID.randomUUID();
    private final UUID leaver = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();

    private UUID chat() {
        UUID chatId = UUID.randomUUID();
        jdbcTemplate.update("insert into public.chat (id) values (?)", chatId);
        return chatId;
    }

    private UUID session(UUID chatId, String mode) {
        UUID sessionId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into public.shared_session (id, chat_id, mode, agent_name, status, created, updated)
                values (?, ?, ?, ?, 'ACTIVE', now(), now())
                """, sessionId, chatId, mode, "AGENT".equals(mode) ? "feud" : null);
        return sessionId;
    }

    private void seat(UUID sessionId, UUID userId, int seat, boolean left) {
        jdbcTemplate.update("""
                insert into public.session_participant (id, session_id, user_id, seat, name, joined, left_at)
                values (?, ?, ?, ?, ?, now(), ?)
                """, UUID.randomUUID(), sessionId, userId, seat, "user" + seat, left ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
    }

    private UUID image(UUID userId, UUID chatId) {
        UUID imageId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into public.generated_image (id, user_id, chat_id, prompt, sha256, content_type, image_data, file_size_bytes, created)
                values (?, ?, ?, 'a lighthouse', 'abc', 'image/png', ?, 3, now())
                """, imageId, userId, chatId, new byte[]{1, 2, 3});
        return imageId;
    }

    private UUID conversationImage() {
        UUID chatId = chat();
        UUID sessionId = session(chatId, "CONVERSATION");
        seat(sessionId, owner, 0, false);
        seat(sessionId, seated, 1, false);
        seat(sessionId, leaver, 2, true);

        return image(seated, chatId);
    }

    @Test
    void test_the_owner_reads_their_own_image() {
        UUID imageId = conversationImage();

        assertThat(generatedImageRepository.findReadableByIdAndUserId(imageId, seated)).isPresent();
    }

    @Test
    void test_a_seated_participant_of_the_conversation_reads_an_image_someone_else_generated() {
        UUID imageId = conversationImage();

        assertThat(generatedImageRepository.findReadableByIdAndUserId(imageId, owner)).isPresent();
    }

    @Test
    void test_a_participant_who_left_cannot_read_it() {
        UUID imageId = conversationImage();

        assertThat(generatedImageRepository.findReadableByIdAndUserId(imageId, leaver)).isEmpty();
    }

    @Test
    void test_someone_outside_the_session_cannot_read_it() {
        UUID imageId = conversationImage();

        assertThat(generatedImageRepository.findReadableByIdAndUserId(imageId, stranger)).isEmpty();
    }

    @Test
    void test_a_private_chats_image_is_readable_only_by_its_owner() {
        UUID imageId = image(owner, chat());

        assertThat(generatedImageRepository.findReadableByIdAndUserId(imageId, owner)).isPresent();
        assertThat(generatedImageRepository.findReadableByIdAndUserId(imageId, seated)).isEmpty();
        assertThat(generatedImageRepository.findReadableByIdAndUserId(imageId, stranger)).isEmpty();
    }

    @Test
    void test_an_image_with_no_chat_is_readable_only_by_its_owner() {
        UUID imageId = image(owner, null);

        assertThat(generatedImageRepository.findReadableByIdAndUserId(imageId, owner)).isPresent();
        assertThat(generatedImageRepository.findReadableByIdAndUserId(imageId, seated)).isEmpty();
    }

    @Test
    void test_an_agent_sessions_participant_cannot_read_an_image_of_its_chat() {
        UUID chatId = chat();
        UUID sessionId = session(chatId, "AGENT");
        seat(sessionId, owner, 0, false);
        seat(sessionId, seated, 1, false);

        UUID imageId = image(owner, chatId);

        assertThat(generatedImageRepository.findReadableByIdAndUserId(imageId, seated)).isEmpty();
    }
}
