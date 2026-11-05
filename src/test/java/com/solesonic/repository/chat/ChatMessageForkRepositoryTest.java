package com.solesonic.repository.chat;

import org.springframework.ai.chat.messages.MessageType;
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
 * The fork insert against a real Postgres built by the full Flyway history: which rows it copies,
 * in what order, with which columns, and who it attributes them to. A native statement is not checked
 * by the compiler or by startup, so only a database can prove it.
 * <p>
 * Skipped where Docker is unavailable.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class ChatMessageForkRepositoryTest {

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
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private int fork(UUID source, UUID target, UUID sender, int limit) {
        return new TransactionTemplate(transactionManager).execute(_ -> chatMessageRepository.forkRecent(source, target, sender, limit));
    }

    private static final ZonedDateTime BASE = ZonedDateTime.parse("2026-10-26T12:00:00Z");

    private UUID message(UUID chatId, MessageType type, String text, int minute) {
        UUID id = UUID.randomUUID();

        jdbcTemplate.update("""
                insert into chat_message (id, chat_id, message_type, message, timestamp, elicitation_id, progress_data, commands)
                values (?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb))
                """, id, chatId, type.name(), text, java.sql.Timestamp.from(BASE.plusMinutes(minute).toInstant()),
                type == MessageType.SYSTEM ? UUID.randomUUID() : null,
                type == MessageType.SYSTEM ? "{\"step\":1}" : null,
                "[\"web\"]");

        return id;
    }

    private List<java.util.Map<String, Object>> rows(UUID chatId) {
        return jdbcTemplate.queryForList("""
                select message_type, message, sender_user_id, elicitation_id, progress_data, commands, response_metadata ->> 'model' as model
                  from chat_message where chat_id = ? order by timestamp asc
                """, chatId);
    }

    @Test
    void test_fork_copies_only_user_and_assistant_rows_in_order_attributing_user_rows_to_the_sender() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID owner = UUID.randomUUID();

        message(source, MessageType.USER, "hello", 1);
        UUID assistant = message(source, MessageType.ASSISTANT, "hi there", 2);
        message(source, MessageType.SYSTEM, "progress", 3);
        message(source, MessageType.USER, "and now", 4);
        jdbcTemplate.update("update chat_message set response_metadata = cast(? as jsonb) where id = ?", "{\"model\":\"big\"}", assistant);
        message(UUID.randomUUID(), MessageType.USER, "someone else's", 5);

        int copied = fork(source, target, owner, 500);

        assertThat(copied).isEqualTo(3);
        List<java.util.Map<String, Object>> forked = rows(target);
        assertThat(forked).extracting(row -> row.get("message")).containsExactly("hello", "hi there", "and now");
        assertThat(forked).extracting(row -> row.get("message_type")).containsExactly("USER", "ASSISTANT", "USER");
        assertThat(forked.get(0).get("sender_user_id")).isEqualTo(owner);
        assertThat(forked.get(1).get("sender_user_id")).isNull();
        assertThat(forked.get(2).get("sender_user_id")).isEqualTo(owner);
        assertThat(forked.get(1).get("model")).isEqualTo("big");
        assertThat(forked).allSatisfy(row -> {
            assertThat(row.get("elicitation_id")).isNull();
            assertThat(row.get("progress_data")).isNull();
            assertThat(String.valueOf(row.get("commands"))).isEqualTo("[]");
        });
        assertThat(rows(source)).hasSize(4);
    }

    @Test
    void test_fork_keeps_timestamps_and_only_the_most_recent_rows_up_to_the_limit() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        message(source, MessageType.USER, "one", 1);
        message(source, MessageType.ASSISTANT, "two", 2);
        message(source, MessageType.USER, "three", 3);
        message(source, MessageType.ASSISTANT, "four", 4);

        int copied = fork(source, target, UUID.randomUUID(), 4);

        assertThat(copied).isEqualTo(4);
        assertThat(rows(target)).extracting(row -> row.get("message")).containsExactly("one", "two", "three", "four");

        UUID narrower = UUID.randomUUID();
        message(source, MessageType.USER, "five", 5);

        assertThat(fork(source, narrower, UUID.randomUUID(), 4)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForList("select timestamp from chat_message where chat_id = ? order by timestamp", Timestamp.class, narrower))
                .extracting(Timestamp::toInstant)
                .containsExactly(BASE.plusMinutes(3).toInstant(), BASE.plusMinutes(4).toInstant(), BASE.plusMinutes(5).toInstant());
    }

    @Test
    void test_the_copied_window_starts_with_a_user_turn() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        message(source, MessageType.USER, "one", 1);
        message(source, MessageType.ASSISTANT, "two", 2);
        message(source, MessageType.USER, "three", 3);
        message(source, MessageType.ASSISTANT, "four", 4);
        message(source, MessageType.ASSISTANT, "five", 5);

        // The newest 4 are two..five; "two" answers a question the window cut off.
        assertThat(fork(source, target, UUID.randomUUID(), 4)).isEqualTo(3);
        assertThat(rows(target)).extracting(row -> row.get("message")).containsExactly("three", "four", "five");
    }

    @Test
    void test_a_window_of_only_assistant_rows_copies_nothing() {
        UUID source = UUID.randomUUID();

        message(source, MessageType.USER, "one", 1);
        message(source, MessageType.ASSISTANT, "two", 2);
        message(source, MessageType.ASSISTANT, "three", 3);

        assertThat(fork(source, UUID.randomUUID(), UUID.randomUUID(), 2)).isZero();
    }

    @Test
    void test_rows_with_the_same_timestamp_are_copied_in_a_stable_order() {
        UUID source = UUID.randomUUID();

        for (int i = 0; i < 6; i++) {
            message(source, i % 2 == 0 ? MessageType.USER : MessageType.ASSISTANT, "m" + i, 1);
        }

        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        fork(source, first, UUID.randomUUID(), 500);
        fork(source, second, UUID.randomUUID(), 500);

        assertThat(jdbcTemplate.queryForList("select message from chat_message where chat_id = ? order by ctid", String.class, first))
                .isEqualTo(jdbcTemplate.queryForList("select message from chat_message where chat_id = ? order by ctid", String.class, second));
    }

    @Test
    void test_fork_of_an_empty_chat_copies_nothing() {
        assertThat(fork(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 10)).isZero();
    }
}
