package com.solesonic.repository.chat;


import java.sql.Timestamp;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.repository.session.SharedSessionRepository;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.data.domain.PageRequest;

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
 * What a user's chat history shows, against a real Postgres built by the full Flyway history: their own
 * conversations plus the shared conversations they are still seated in, and nothing of a session they left,
 * never joined, that is an agent session, or that is over. Also the plan of the list query on a few
 * thousand rows, since it is the hottest query in the API.
 * <p>
 * Skipped where Docker is unavailable.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class ChatHistorySharedRepositoryTest {

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
    private ChatRepository chatRepository;

    @Autowired
    private SharedSessionRepository sharedSessionRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final UUID NONE = new UUID(0, 0);
    private static final Set<MessageType> TYPES = Set.of(MessageType.USER, MessageType.ASSISTANT);

    private final UUID owner = UUID.randomUUID();
    private final UUID guest = UUID.randomUUID();
    private final UUID leaver = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();

    private UUID chat(UUID userId, String kind, String name, UUID groupId, Integer sortOrder, int minutesAgo) {
        UUID chatId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into public.chat (id, user_id, kind, name, chat_group_id, sort_order, timestamp)
                values (?, ?, ?, ?, ?, ?, now() - make_interval(mins => ?))
                """, chatId, userId, kind, name, groupId, sortOrder, minutesAgo);
        return chatId;
    }

    private UUID group(UUID userId) {
        UUID groupId = UUID.randomUUID();
        jdbcTemplate.update("insert into public.chat_group (id, user_id, name, timestamp) values (?, ?, 'Work', now())",
                groupId, userId);
        return groupId;
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
                """, UUID.randomUUID(), sessionId, userId, seat, "user" + seat,
                left ? Timestamp.from(java.time.Instant.now()) : null);
    }

    /** A live shared conversation: owner in seat 0, guest in seat 1, leaver gone. */
    private UUID sharedChat(String name, UUID groupId, int minutesAgo) {
        UUID chatId = chat(owner, "SHARED", name, groupId, null, minutesAgo);
        UUID sessionId = session(chatId, "CONVERSATION", "ACTIVE");
        seat(sessionId, owner, 0, false);
        seat(sessionId, guest, 1, false);
        seat(sessionId, leaver, 2, true);
        return chatId;
    }

    private List<UUID> visible(UUID userId) {
        List<UUID> ids = sharedSessionRepository.findVisibleConversationChatIds(userId, org.springframework.data.domain.Limit.of(500));
        return ids.isEmpty() ? List.of(NONE) : ids;
    }

    private List<UUID> listed(UUID userId) {
        return chatRepository.findByUserId(userId, visible(userId), PageRequest.of(0, 50))
                .map(Chat::getId).getContent();
    }

    private List<UUID> ungrouped(UUID userId) {
        return chatRepository.findUngroupedByUserId(userId, visible(userId), PageRequest.of(0, 50))
                .map(Chat::getId).getContent();
    }

    @Test
    void test_the_owner_and_a_seated_participant_both_list_a_shared_chat() {
        UUID shared = sharedChat("Trip", null, 5);

        assertThat(listed(owner)).containsExactly(shared);
        assertThat(listed(guest)).containsExactly(shared);
    }

    @Test
    void test_someone_who_left_or_never_joined_does_not_list_it() {
        sharedChat("Trip", null, 5);

        assertThat(listed(leaver)).isEmpty();
        assertThat(listed(stranger)).isEmpty();
        assertThat(ungrouped(leaver)).isEmpty();
        assertThat(ungrouped(stranger)).isEmpty();
    }

    @Test
    void test_an_agent_session_a_closed_session_and_an_expired_one_are_not_listed() {
        UUID agentChat = chat(owner, "SHARED", "Feud", null, null, 1);
        UUID agentSession = session(agentChat, "AGENT", "ACTIVE");
        seat(agentSession, owner, 0, false);
        seat(agentSession, guest, 1, false);

        for (String status : List.of("CLOSED", "EXPIRED")) {
            UUID chatId = chat(owner, "SHARED", status, null, null, 1);
            UUID sessionId = session(chatId, "CONVERSATION", status);
            seat(sessionId, owner, 0, false);
            seat(sessionId, guest, 1, false);
        }

        assertThat(listed(owner)).isEmpty();
        assertThat(listed(guest)).isEmpty();
    }

    @Test
    void test_private_chats_still_list_for_their_owner_only_and_a_shared_row_sits_in_activity_order() {
        UUID older = chat(guest, "CONVERSATION", "Older", null, null, 30);
        UUID shared = sharedChat("Trip", null, 10);
        UUID newest = chat(guest, "CONVERSATION", "Newest", null, null, 1);
        chat(owner, "CONVERSATION", "Not yours", null, null, 1);

        assertThat(listed(guest)).containsExactly(newest, shared, older);
    }

    @Test
    void test_a_participant_is_not_ordered_by_the_owners_hand_placed_position() {
        UUID shared = sharedChat("Trip", null, 10);
        jdbcTemplate.update("update public.chat set sort_order = 0 where id = ?", shared);
        UUID newest = chat(guest, "CONVERSATION", "Newest", null, null, 1);

        assertThat(listed(guest)).containsExactly(newest, shared);
        assertThat(listed(owner)).containsExactly(shared);
    }

    @Test
    void test_the_owners_grouped_shared_chat_lists_in_the_group_and_not_for_others() {
        UUID groupId = group(owner);
        UUID shared = sharedChat("Trip", groupId, 5);

        assertThat(chatRepository.findByUserIdAndChatGroupId(owner, groupId, visible(owner), PageRequest.of(0, 50))
                .map(Chat::getId).getContent()).containsExactly(shared);
        assertThat(chatRepository.findByUserIdAndChatGroupId(guest, groupId, visible(guest), PageRequest.of(0, 50))
                .getContent()).isEmpty();
    }

    @Test
    void test_ungrouped_shows_a_non_owners_shared_chat_even_when_the_owner_filed_it_but_hides_the_owners_grouped_one() {
        UUID shared = sharedChat("Trip", group(owner), 5);

        assertThat(ungrouped(guest)).containsExactly(shared);
        assertThat(ungrouped(owner)).isEmpty();
    }

    @Test
    void test_search_finds_a_shared_chat_by_name_and_by_message_for_a_seated_participant_only() {
        UUID byName = sharedChat("Zanzibar plans", null, 5);
        UUID byMessage = sharedChat("Other", null, 6);
        jdbcTemplate.update("""
                insert into public.chat_message (id, chat_id, message, message_type, timestamp)
                values (?, ?, 'ask about zanzibar flights', 'USER', now())
                """, UUID.randomUUID(), byMessage);

        assertThat(chatRepository.searchByUserId(guest, visible(guest), "%zanzibar%", TYPES, PageRequest.of(0, 50))
                .map(Chat::getId).getContent()).containsExactly(byName, byMessage);
        assertThat(chatRepository.searchUngroupedByUserId(guest, visible(guest), "%zanzibar%", TYPES, PageRequest.of(0, 50))
                .map(Chat::getId).getContent()).containsExactly(byName, byMessage);
        assertThat(chatRepository.searchByUserId(leaver, visible(leaver), "%zanzibar%", TYPES, PageRequest.of(0, 50))
                .getContent()).isEmpty();
        assertThat(chatRepository.searchByUserId(stranger, visible(stranger), "%zanzibar%", TYPES, PageRequest.of(0, 50))
                .getContent()).isEmpty();
    }

    @Test
    void test_the_placed_arrangement_includes_the_owners_visible_shared_chat_only() {
        UUID groupId = group(owner);
        UUID shared = sharedChat("Trip", groupId, 5);
        jdbcTemplate.update("update public.chat set sort_order = 0, group_sort_order = 0 where id = ?", shared);

        UUID closed = chat(owner, "SHARED", "Over", groupId, 1, 1);
        session(closed, "CONVERSATION", "CLOSED");
        jdbcTemplate.update("update public.chat set group_sort_order = 1 where id = ?", closed);

        assertThat(chatRepository.findPlacedByUserId(owner, visible(owner))).extracting(Chat::getId).containsExactly(shared);
        assertThat(chatRepository.findPlacedByUserIdAndChatGroupId(owner, groupId, visible(owner)))
                .extracting(Chat::getId).containsExactly(shared);
    }

    @Test
    void test_the_visible_shared_lookup_answers_seated_participants_only() {
        UUID shared = sharedChat("Trip", null, 5);

        assertThat(chatRepository.findVisibleSharedById(shared, owner)).isPresent();
        assertThat(chatRepository.findVisibleSharedById(shared, guest)).isPresent();
        assertThat(chatRepository.findVisibleSharedById(shared, leaver)).isEmpty();
        assertThat(chatRepository.findVisibleSharedById(shared, stranger)).isEmpty();
        assertThat(chatRepository.findVisibleSharedById(chat(owner, "CONVERSATION", "Mine", null, null, 1), owner)).isEmpty();
    }

    @Test
    void test_clearing_a_group_ungroups_a_shared_chat_without_deleting_it_or_its_session() {
        UUID groupId = group(owner);
        UUID shared = sharedChat("Trip", groupId, 5);

        int cleared = new TransactionTemplate(transactionManager).execute(status -> chatRepository.clearChatGroup(owner, groupId));

        assertThat(cleared).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select chat_group_id from public.chat where id = ?", UUID.class, shared)).isNull();
        assertThat(jdbcTemplate.queryForObject("select count(*) from public.shared_session where chat_id = ?", Integer.class, shared)).isEqualTo(1);
    }

    @Test
    void test_the_list_plan_uses_indexes_rather_than_scanning_the_chat_table() {
        jdbcTemplate.update("""
                insert into public.chat (id, user_id, kind, name, timestamp)
                select gen_random_uuid(), ('00000000-0000-0000-0000-' || lpad((n % 300)::text, 12, '0'))::uuid,
                       'CONVERSATION', 'c' || n, now() - make_interval(secs => n)
                  from generate_series(1, 30000) n
                """);
        UUID shared = sharedChat("Trip", null, 5);
        jdbcTemplate.execute("analyze public.chat");
        jdbcTemplate.execute("analyze public.shared_session");
        jdbcTemplate.execute("analyze public.session_participant");

        String plan = String.join("\n", jdbcTemplate.queryForList("""
                explain
                select c.* from public.chat c
                 where (c.user_id = ? and c.kind = 'CONVERSATION') or c.id in (?)
                 order by case when c.user_id = ? then c.sort_order end asc nulls last, c.timestamp desc, c.id desc
                 limit 20
                """, String.class, guest, shared, guest));

        System.out.println(plan);
        assertThat(plan).doesNotContainPattern("Seq Scan on chat( |\n)");
        assertThat(listed(guest)).contains(shared);
    }
}
