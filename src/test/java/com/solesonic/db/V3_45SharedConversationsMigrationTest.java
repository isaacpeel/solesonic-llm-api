package com.solesonic.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs {@code V3_42} and then {@code V3_45} against a real Postgres: the constraints are the point, and
 * they are plain SQL. Only the tables V3_42 alters or references are created first, as stubs.
 * <p>
 * Skipped, not failed, where Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class V3_45SharedConversationsMigrationTest {

    private static final String CREATE_SESSIONS = "/db/migration/V3_42__create_shared_sessions.sql";
    private static final String MIGRATION = "/db/migration/V3_45__shared_conversations.sql";

    @Container
    @SuppressWarnings("resource")
    private static final GenericContainer<?> POSTGRES = new GenericContainer<>("pgvector/pgvector:0.8.0-pg17")
            .withEnv("POSTGRES_USER", "test")
            .withEnv("POSTGRES_PASSWORD", "test")
            .withEnv("POSTGRES_DB", "test")
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    private Connection connection;

    @BeforeEach
    void beforeEach() throws Exception {
        String url = "jdbc:postgresql://%s:%d/test".formatted(POSTGRES.getHost(), POSTGRES.getMappedPort(5432));

        connection = DriverManager.getConnection(url, "test", "test");

        try (Statement statement = connection.createStatement()) {
            statement.execute("drop schema public cascade; create schema public;");
            statement.execute("""
                    create table public.chat (id uuid primary key);
                    create table public.chat_message (id uuid primary key);
                    create table public.push_preference (user_id uuid primary key);
                    create table public.user_profile (
                        user_id uuid primary key,
                        username varchar(255),
                        removed timestamptz);
                    """);
        }

        run(CREATE_SESSIONS);
        run(MIGRATION);
    }

    @AfterEach
    void afterEach() throws SQLException {
        connection.close();
    }

    @Test
    void test_existing_agent_sessions_become_agent_mode() throws Exception {
        UUID session = session("AGENT", "feud");

        try (PreparedStatement statement = connection.prepareStatement("select mode from public.shared_session where id = ?")) {
            statement.setObject(1, session);
            var resultSet = statement.executeQuery();
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("AGENT");
        }
    }

    @Test
    void test_mode_must_be_a_known_value() {
        assertThatThrownBy(() -> session("OTHER", null)).isInstanceOf(SQLException.class);
    }

    @Test
    void test_agent_name_is_required_for_agent_mode_and_forbidden_for_conversation_mode() throws Exception {
        assertThatThrownBy(() -> session("AGENT", null)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> session("CONVERSATION", "feud")).isInstanceOf(SQLException.class);

        assertThat(session("CONVERSATION", null)).isNotNull();
        assertThat(session("AGENT", "feud")).isNotNull();
    }

    @Test
    void test_invite_agent_name_is_nullable() throws Exception {
        assertThat(invite(UUID.randomUUID(), UUID.randomUUID(), session("CONVERSATION", null), null)).isNotNull();
    }

    @Test
    void test_pending_invites_to_one_user_in_different_sessions_are_allowed() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID invitee = UUID.randomUUID();

        invite(owner, invitee, session("CONVERSATION", null), null);
        invite(owner, invitee, session("CONVERSATION", null), null);
    }

    @Test
    void test_duplicate_pending_invite_in_one_session_is_rejected() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID invitee = UUID.randomUUID();
        UUID session = session("CONVERSATION", null);

        invite(owner, invitee, session, null);

        assertThatThrownBy(() -> invite(owner, invitee, session, null)).isInstanceOf(SQLException.class);
    }

    @Test
    void test_a_decided_invite_does_not_block_a_new_one_in_the_same_session() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID invitee = UUID.randomUUID();
        UUID session = session("CONVERSATION", null);

        invite(owner, invitee, session, null);
        execute("update public.session_invite set status = 'CANCELLED'");

        invite(owner, invitee, session, null);
    }

    @Test
    void test_legacy_pair_rule_still_holds_for_invites_without_a_session() throws Exception {
        UUID alex = UUID.randomUUID();
        UUID sam = UUID.randomUUID();

        invite(alex, sam, null, "feud");

        assertThatThrownBy(() -> invite(alex, sam, null, "feud")).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> invite(sam, alex, null, "feud")).isInstanceOf(SQLException.class);
    }

    @Test
    void test_deleting_a_session_deletes_its_invites() throws Exception {
        UUID session = session("CONVERSATION", null);
        invite(UUID.randomUUID(), UUID.randomUUID(), session, null);

        execute("delete from public.shared_session");

        try (Statement statement = connection.createStatement()) {
            var resultSet = statement.executeQuery("select count(*) from public.session_invite");
            resultSet.next();
            assertThat(resultSet.getInt(1)).isZero();
        }
    }

    @Test
    void test_source_chat_is_informational_and_has_no_foreign_key() throws Exception {
        UUID chat = UUID.randomUUID();
        execute("insert into public.chat (id) values ('" + chat + "')");

        UUID session = session("CONVERSATION", null);
        execute("update public.shared_session set source_chat_id = '" + UUID.randomUUID() + "' where id = '" + session + "'");
    }

    private UUID session(String mode, String agentName) throws SQLException {
        UUID chat = UUID.randomUUID();
        UUID id = UUID.randomUUID();

        execute("insert into public.chat (id) values ('" + chat + "')");

        try (PreparedStatement statement = connection.prepareStatement("""
                insert into public.shared_session (id, chat_id, mode, agent_name, status, created, updated)
                values (?, ?, ?, ?, 'ACTIVE', now(), now())
                """)) {
            statement.setObject(1, id);
            statement.setObject(2, chat);
            statement.setString(3, mode);
            statement.setString(4, agentName);
            statement.executeUpdate();
        }

        return id;
    }

    private UUID invite(UUID inviter, UUID invitee, UUID sessionId, String agentName) throws SQLException {
        UUID id = UUID.randomUUID();

        try (PreparedStatement statement = connection.prepareStatement("""
                insert into public.session_invite
                    (id, inviter_user_id, invitee_user_id, agent_name, status, session_id, created, expires)
                values (?, ?, ?, ?, 'PENDING', ?, now(), now() + interval '1 day')
                """)) {
            statement.setObject(1, id);
            statement.setObject(2, inviter);
            statement.setObject(3, invitee);
            statement.setString(4, agentName);
            statement.setObject(5, sessionId);
            statement.executeUpdate();
        }

        return id;
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void run(String resource) throws IOException, SQLException {
        String sql;

        try (InputStream inputStream = getClass().getResourceAsStream(resource)) {
            assertThat(inputStream).as(resource).isNotNull();
            sql = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }

        execute(sql.replace("${DB_OWNER}", "test"));
    }
}
