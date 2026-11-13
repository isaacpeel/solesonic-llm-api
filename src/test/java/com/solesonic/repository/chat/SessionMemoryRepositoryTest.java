package com.solesonic.repository.chat;

import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.model.chat.history.SessionLinks;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.jdbc.JdbcSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The session store and the {@code chat_message} link against a real Postgres built by the full Flyway
 * history: that the copied DDL is what Spring AI's JDBC repository expects, that a session write joins
 * the application's transaction, and that the link queries select and guard what the session code
 * relies on.
 * <p>
 * Skipped where Docker is unavailable.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class SessionMemoryRepositoryTest {

    @Container
    @SuppressWarnings("all")
    private static final GenericContainer<?> POSTGRES = new GenericContainer<>("pgvector/pgvector:0.8.0-pg17")
            .withEnv("POSTGRES_USER", "test")
            .withEnv("POSTGRES_PASSWORD", "test")
            .withEnv("POSTGRES_DB", "test")
            .withExposedPorts(5432)
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

    @SpringBootConfiguration
    @EntityScan(basePackages = "com.solesonic.model")
    @EnableJpaRepositories(basePackages = "com.solesonic.repository")
    @SuppressWarnings("all")
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

    private static final ZonedDateTime BASE = ZonedDateTime.parse("2026-11-06T12:00:00Z");
    private static final Set<MessageType> HISTORY_TYPES = Set.of(MessageType.USER, MessageType.ASSISTANT);

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private SessionService sessionService() {
        return DefaultSessionService.builder()
                .sessionRepository(JdbcSessionRepository.builder()
                        .dataSource(dataSource)
                        .transactionManager(transactionManager)
                        .build())
                .build();
    }

    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(_ -> work.get());
    }

    private UUID message(UUID chatId, MessageType type, String text, int minute) {
        UUID id = UUID.randomUUID();

        jdbcTemplate.update("""
                insert into chat_message (id, chat_id, message_type, message, timestamp)
                values (?, ?, ?, ?, ?)
                """, id, chatId, type.name(), text, Timestamp.from(BASE.plusMinutes(minute).toInstant()));

        return id;
    }

    private String link(UUID chatMessageId) {
        return jdbcTemplate.queryForObject("select session_event_id from chat_message where id = ?", String.class, chatMessageId);
    }

    @Test
    void theJdbcSessionRepositoryWorksOnTheMigratedSchema() {
        SessionService sessionService = sessionService();
        String sessionId = UUID.randomUUID().toString();
        String ownerId = UUID.randomUUID().toString();

        sessionService.create(CreateSessionRequest.builder().id(sessionId).metadata(SessionAttributes.PRINCIPAL_NAME, ownerId).build());
        long version = sessionService.getEventVersion(sessionId);
        boolean appended = sessionService.appendEvents(sessionId, List.of(
                SessionEvent.builder().id("chat-message:a").sessionId(sessionId).message(new UserMessage("hello")).build(),
                SessionEvent.builder().id("chat-message:b").sessionId(sessionId).message(new AssistantMessage("hi")).build()), version);

        assertThat(appended).isTrue();
        assertThat(sessionService.getEvents(sessionId, EventFilter.active())).extracting(SessionEvent::getId)
                .containsExactly("chat-message:a", "chat-message:b");
        assertThat(sessionService.findByMetadata(SessionAttributes.PRINCIPAL_NAME, ownerId)).hasSize(1);

        sessionService.delete(sessionId);

        assertThat(sessionService.findById(sessionId)).isNull();
        assertThat(jdbcTemplate.queryForObject("select count(*) from ai_session_event where session_id = ?", Integer.class, sessionId))
                .isZero();
    }

    /**
     * A session write and a {@code chat_message} write commit together or not at all: the copy links
     * rows in the same transaction it appends their events in.
     */
    @Test
    void aSessionWriteJoinsTheApplicationsTransaction() {
        SessionService sessionService = sessionService();
        String sessionId = UUID.randomUUID().toString();
        sessionService.create(CreateSessionRequest.builder().id(sessionId).build());

        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(_ -> {
                sessionService.appendMessage(sessionId, new UserMessage("rolled back"));
                throw new IllegalStateException("the turn failed");
            });
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("the turn failed");
        }

        assertThat(sessionService.getMessages(sessionId)).isEmpty();
    }

    @Test
    void findUnlinkedReturnsOnlyUnseenHistoryOlderThanTheInFlightRowOldestFirst() {
        UUID chatId = UUID.randomUUID();
        UUID question = message(chatId, MessageType.USER, "question", 1);
        UUID answer = message(chatId, MessageType.ASSISTANT, "answer", 2);
        message(chatId, MessageType.SYSTEM, "Chat canceled.", 3);
        UUID linked = message(chatId, MessageType.USER, "already in the session", 4);
        jdbcTemplate.update("update chat_message set session_event_id = 'advisor-stored' where id = ?", linked);
        UUID inFlight = message(chatId, MessageType.USER, "in flight", 5);
        message(chatId, MessageType.USER, "another tab", 6);
        message(UUID.randomUUID(), MessageType.USER, "someone else's", 1);

        List<ChatMessage> unlinked = chatMessageRepository.findUnlinked(chatId, HISTORY_TYPES,
                BASE.plusMinutes(5), inFlight);

        assertThat(unlinked).extracting(ChatMessage::getId).containsExactly(question, answer);
    }

    /**
     * A row is linked once: a second link, from a concurrent copy or a late completion, never moves it.
     */
    @Test
    void linkSessionEventLinksOnlyRowsTheSessionHasNotSeen() {
        UUID chatId = UUID.randomUUID();
        UUID first = message(chatId, MessageType.USER, "first", 1);
        UUID second = message(chatId, MessageType.USER, "second", 2);

        int linkedOnce = inTransaction(() -> chatMessageRepository.linkSessionEvent(chatId, List.of(first), "event-1"));
        int linkedAgain = inTransaction(() -> chatMessageRepository.linkSessionEvent(chatId, List.of(first, second), SessionLinks.OUTSIDE_WINDOW));
        int otherChat = inTransaction(() -> chatMessageRepository.linkSessionEvent(UUID.randomUUID(), List.of(second), "event-x"));

        assertThat(linkedOnce).isEqualTo(1);
        assertThat(linkedAgain).isEqualTo(1);
        assertThat(otherChat).isZero();
        assertThat(link(first)).isEqualTo("event-1");
        assertThat(link(second)).isEqualTo(SessionLinks.OUTSIDE_WINDOW);
    }

    @Test
    void unlinkSessionForgetsEveryLinkOfOneChat() {
        UUID chatId = UUID.randomUUID();
        UUID otherChatId = UUID.randomUUID();
        UUID row = message(chatId, MessageType.USER, "mine", 1);
        UUID otherRow = message(otherChatId, MessageType.USER, "theirs", 1);
        jdbcTemplate.update("update chat_message set session_event_id = 'event' where id in (?, ?)", row, otherRow);

        int unlinked = inTransaction(() -> chatMessageRepository.unlinkSession(chatId));

        assertThat(unlinked).isEqualTo(1);
        assertThat(link(row)).isNull();
        assertThat(link(otherRow)).isEqualTo("event");
    }

    /**
     * The link is the session code's bookkeeping; the entity is returned to clients as it stands.
     */
    @Test
    void theLinkIsNeverSerialized() {
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setSessionEventId("event-1");
        chatMessage.setMessage("hello");

        String json = JsonMapper.builder().build().writeValueAsString(chatMessage);

        assertThat(json).doesNotContain("sessionEventId").doesNotContain("event-1");
    }
}
