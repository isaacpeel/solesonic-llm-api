package com.solesonic.repository.user;

import com.solesonic.model.user.UserDirectoryEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UserProfileRepository#searchActive} against a real Postgres, since the point is the SQL. Only
 * {@code user_profile} is created, in its production shape (V3_38, V3_40).
 * <p>
 * Skipped, not failed, where Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class UserProfileRepositorySearchTest {
    private static final UUID CALLER = UUID.randomUUID();

    @Container
    @SuppressWarnings("resource")
    private static final GenericContainer<?> POSTGRES = new GenericContainer<>("pgvector/pgvector:0.8.0-pg17")
            .withEnv("POSTGRES_USER", "test")
            .withEnv("POSTGRES_PASSWORD", "test")
            .withEnv("POSTGRES_DB", "test")
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    private JdbcTemplate jdbcTemplate;
    private UserProfileRepository repository;

    @BeforeEach
    void beforeEach() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:postgresql://%s:%d/test".formatted(POSTGRES.getHost(), POSTGRES.getMappedPort(5432)), "test", "test");

        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("drop table if exists public.user_profile");
        jdbcTemplate.execute("""
                create table public.user_profile (
                    user_id uuid primary key,
                    username varchar(255),
                    email varchar(320),
                    display_name varchar(128),
                    updated timestamptz not null default now(),
                    removed timestamptz)
                """);

        repository = new UserProfileRepository(JdbcClient.create(jdbcTemplate));
    }

    @AfterEach
    void afterEach() {
        jdbcTemplate.execute("drop table if exists public.user_profile");
    }

    @Test
    void excludesTheCallerAndRemovedUsersAndThoseWithoutAUsername() {
        profile(CALLER, "alice", "Alice", false);
        profile(UUID.randomUUID(), "bob", "Bob", false);
        profile(UUID.randomUUID(), "bobby", "Gone", true);
        profile(UUID.randomUUID(), null, "Nameless", false);

        assertThat(usernames("", 20)).containsExactly("bob");
    }

    @Test
    void matchesUsernamePrefixOrDisplayNameSubstringCaseInsensitively() {
        profile(UUID.randomUUID(), "Diana", "Di R.", false);
        profile(UUID.randomUUID(), "dave", "Dave Peel", false);
        profile(UUID.randomUUID(), "erin", "Dana Scully", false);
        profile(UUID.randomUUID(), "xdave", "Someone", false);

        assertThat(usernames("DA", 20)).containsExactly("dave", "erin");
        assertThat(usernames("peel", 20)).containsExactly("dave");
    }

    @Test
    void treatsLikeWildcardsInTheQueryAsLiterals() {
        profile(UUID.randomUUID(), "alice", "Alice", false);
        profile(UUID.randomUUID(), "a_b", "Under", false);
        profile(UUID.randomUUID(), "pct", "100% sure", false);
        profile(UUID.randomUUID(), "slash", "back\\slash", false);

        // Unescaped, "%" would match everyone and "_" any one-character prefix.
        assertThat(usernames("%", 20)).containsExactly("pct");
        assertThat(usernames("_", 20)).isEmpty();
        assertThat(usernames("a_", 20)).containsExactly("a_b");
        assertThat(usernames("k\\s", 20)).containsExactly("slash");
    }

    @Test
    void returnsAUsernameOnceKeepingTheMostRecentlySeenHolder() {
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        profile(older, "dave", "Old Dave", false);
        profile(newer, "Dave", "New Dave", false);
        jdbcTemplate.update("update public.user_profile set updated = now() - interval '1 day' where user_id = ?", older);

        List<UserDirectoryEntry> entries = repository.searchActive("", CALLER, 20);

        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().userId()).isEqualTo(newer);
    }

    @Test
    void ordersByUsernameAndAppliesTheLimit() {
        profile(UUID.randomUUID(), "carol", "C", false);
        profile(UUID.randomUUID(), "Alice", "A", false);
        profile(UUID.randomUUID(), "bob", "B", false);

        assertThat(usernames("", 2)).containsExactly("Alice", "bob");
    }

    private List<String> usernames(String query, int limit) {
        return repository.searchActive(query, CALLER, limit).stream().map(UserDirectoryEntry::username).toList();
    }

    private void profile(UUID userId, String username, String displayName, boolean removed) {
        jdbcTemplate.update("""
                insert into public.user_profile (user_id, username, email, display_name, removed)
                values (?, ?, 'secret@example.com', ?, case when ? then now() end)
                """, userId, username, displayName, removed);
    }
}
