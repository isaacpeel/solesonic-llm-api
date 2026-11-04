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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs {@code V3_44} against a real pgvector Postgres, seeded with every shape of attachment the
 * one-time classification has to tell apart.
 * <p>
 * Only the tables and columns the migration reads are created, in their pre-V3_44 shape (taken from
 * the production schema, {@code \d} in step 0 of the plan). The point is the classification, which
 * is plain SQL over those columns; nothing here depends on Hibernate.
 * <p>
 * Skipped, not failed, where Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class V3_44ChatAttachmentLifecycleMigrationTest {

    private static final String MIGRATION = "/db/migration/V3_44__add_index_lifecycle_to_chat_attachment.sql";

    /**
     * The wipe, as V3_27's {@code installed_on} records it in production.
     */
    private static final Instant WIPE = Instant.parse("2026-09-03T23:32:22Z");

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
    void beforeEach() throws SQLException {
        String url = "jdbc:postgresql://%s:%d/test".formatted(POSTGRES.getHost(), POSTGRES.getMappedPort(5432));

        connection = DriverManager.getConnection(url, "test", "test");

        try (Statement statement = connection.createStatement()) {
            statement.execute("drop schema public cascade; create schema public;");
            statement.execute("""
                    create table public.chat_attachment (
                        id uuid primary key,
                        user_id uuid not null,
                        chat_id uuid,
                        chat_message_id uuid,
                        file_name varchar(255) not null,
                        content_type varchar(255) not null,
                        file_data bytea not null,
                        file_size_bytes bigint not null,
                        created timestamp(6) with time zone not null,
                        chunk_count integer,
                        extraction_failure_reason varchar(255));
                    create table public.chat_message (
                        id uuid primary key,
                        chat_id uuid,
                        "timestamp" timestamp(6) with time zone);
                    create table public.ingested_document (
                        id uuid primary key,
                        document_source varchar(255) not null,
                        metadata jsonb);
                    create table public.flyway_schema_history (
                        installed_rank int primary key,
                        version varchar(50),
                        installed_on timestamp not null default now());
                    """);
        }
    }

    @AfterEach
    void afterEach() throws SQLException {
        connection.close();
    }

    @Test
    void test_classifies_every_attachment_shape_and_is_idempotent() throws Exception {
        recordWipe();

        UUID healthy = attachment(WIPE.minus(10, ChronoUnit.DAYS), 5);
        ingestedDocumentFor(healthy);

        UUID orphan = attachment(WIPE.minus(10, ChronoUnit.DAYS), 5);
        UUID justOutsideMargin = attachment(WIPE.minus(25, ChronoUnit.HOURS), 3);
        UUID insideMargin = attachment(WIPE.minus(23, ChronoUnit.HOURS), 3);
        UUID postWipe = attachment(WIPE.plus(5, ChronoUnit.MINUTES), 4);

        UUID neverIndexed = attachment(WIPE.minus(10, ChronoUnit.DAYS), null);

        UUID rowWithoutChunks = attachment(WIPE.plus(1, ChronoUnit.DAYS), 2);
        ingestedDocumentFor(rowWithoutChunks);

        migrate();

        assertState(healthy, 5, false);
        assertState(orphan, null, false);
        assertState(justOutsideMargin, null, false);
        assertState(insideMargin, 3, true);
        assertState(postWipe, 4, true);
        assertState(neverIndexed, null, false);
        // A row with no chunks is the runtime health check's to judge, not this migration's.
        assertState(rowWithoutChunks, 2, false);

        migrate();

        assertState(orphan, null, false);
        assertState(insideMargin, 3, true);
        assertState(postWipe, 4, true);
        assertState(healthy, 5, false);
    }

    /**
     * Without a recorded wipe nothing can be proven pre-wipe, so every ambiguous row is detached —
     * the direction that can only leave a document unindexed, never resurrect one.
     */
    @Test
    void test_detaches_every_ambiguous_row_when_no_wipe_is_recorded() throws Exception {
        UUID old = attachment(WIPE.minus(100, ChronoUnit.DAYS), 5);

        migrate();

        assertState(old, 5, true);
    }

    /**
     * The send time is the message's, not the attachment's: an attachment staged before the wipe
     * but sent after it was indexed after it, and is ambiguous.
     */
    @Test
    void test_uses_the_message_timestamp_over_the_attachment_created_time() throws Exception {
        recordWipe();

        UUID stagedBeforeSentAfter = attachment(WIPE.minus(10, ChronoUnit.DAYS), 5);
        bindToMessage(stagedBeforeSentAfter, WIPE.plus(1, ChronoUnit.HOURS));

        migrate();

        assertState(stagedBeforeSentAfter, 5, true);
    }

    @Test
    void test_new_columns_default_for_new_rows() throws Exception {
        migrate();

        UUID fresh = attachment(Instant.now(), null);

        try (PreparedStatement statement = connection.prepareStatement(
                "select index_detached, index_claimed_at, index_attempted_at from public.chat_attachment where id = ?")) {
            statement.setObject(1, fresh);

            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getBoolean(1)).isFalse();
                assertThat(resultSet.getTimestamp(2)).isNull();
                assertThat(resultSet.getTimestamp(3)).isNull();
            }
        }
    }

    private void recordWipe() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into public.flyway_schema_history (installed_rank, version, installed_on) values (1, '3.27', ?)")) {
            // installed_on is a zone-less timestamp in the server's zone, which is UTC in the container.
            statement.setTimestamp(1, Timestamp.valueOf(WIPE.atZone(ZoneOffset.UTC).toLocalDateTime()));
            statement.executeUpdate();
        }
    }

    private UUID attachment(Instant sentAt, Integer chunkCount) throws SQLException {
        UUID attachmentId = UUID.randomUUID();
        UUID chatId = UUID.randomUUID();
        UUID chatMessageId = UUID.randomUUID();

        try (PreparedStatement message = connection.prepareStatement(
                "insert into public.chat_message (id, chat_id, \"timestamp\") values (?, ?, ?)")) {
            message.setObject(1, chatMessageId);
            message.setObject(2, chatId);
            message.setTimestamp(3, Timestamp.from(sentAt));
            message.executeUpdate();
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                insert into public.chat_attachment
                    (id, user_id, chat_id, chat_message_id, file_name, content_type, file_data, file_size_bytes, created, chunk_count)
                values (?, ?, ?, ?, 'notes.pdf', 'application/pdf', ?, 5, ?, ?)
                """)) {
            statement.setObject(1, attachmentId);
            statement.setObject(2, UUID.randomUUID());
            statement.setObject(3, chatId);
            statement.setObject(4, chatMessageId);
            statement.setBytes(5, "hello".getBytes(StandardCharsets.UTF_8));
            statement.setTimestamp(6, Timestamp.from(sentAt.minus(1, ChronoUnit.MINUTES)));
            statement.setObject(7, chunkCount);
            statement.executeUpdate();
        }

        return attachmentId;
    }

    private void bindToMessage(UUID attachmentId, Instant sentAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                update public.chat_message set "timestamp" = ?
                 where id = (select chat_message_id from public.chat_attachment where id = ?)
                """)) {
            statement.setTimestamp(1, Timestamp.from(sentAt));
            statement.setObject(2, attachmentId);
            statement.executeUpdate();
        }
    }

    private void ingestedDocumentFor(UUID attachmentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into public.ingested_document (id, document_source, metadata) values (?, 'CHAT', ?::jsonb)")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, "{\"CHAT_ATTACHMENT_ID\": \"" + attachmentId + "\"}");
            statement.executeUpdate();
        }
    }

    private void migrate() throws IOException, SQLException {
        String sql;

        try (InputStream inputStream = getClass().getResourceAsStream(MIGRATION)) {
            assertThat(inputStream).as(MIGRATION).isNotNull();
            sql = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }

        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void assertState(UUID attachmentId, Integer chunkCount, boolean detached) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select chunk_count, index_detached from public.chat_attachment where id = ?")) {
            statement.setObject(1, attachmentId);

            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat((Integer) resultSet.getObject(1)).as("chunk_count of " + attachmentId).isEqualTo(chunkCount);
                assertThat(resultSet.getBoolean(2)).as("index_detached of " + attachmentId).isEqualTo(detached);
            }
        }
    }
}
