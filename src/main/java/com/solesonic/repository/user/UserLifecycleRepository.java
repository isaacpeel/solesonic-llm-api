package com.solesonic.repository.user;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Who owns memory data, whether they are still around, and the purge for those who are not.
 * Activity is a {@code USER} message: an interest agent writes {@code ASSISTANT} messages into its
 * owner's chats, and those must not keep a dormant user looking active.
 */
@Repository
public class UserLifecycleRepository {
    private static final String MEMORY_OWNERS = """
            select user_id from user_memory
             union select user_id from chat_memory_reflection
             union select user_id from interest_scan
             union select user_id from interest_offer
             union select user_id from interest_agent
            """;

    private static final List<String> MEMORY_TABLES =
            List.of("interest_offer", "interest_agent", "interest_scan", "chat_memory_reflection", "user_memory");

    private final JdbcClient jdbcClient;

    public UserLifecycleRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Owners Keycloak has not been asked about since {@code checkedBefore}. A user already known to be
     * removed is not asked again.
     */
    public List<UUID> ownersToCheck(ZonedDateTime checkedBefore) {
        return jdbcClient.sql("""
                        select owners.user_id
                          from (%s) owners
                          left join user_profile profile on profile.user_id = owners.user_id
                         where profile.removed is null
                           and (profile.checked is null or profile.checked < :checkedBefore)
                         order by profile.checked nulls first
                        """.formatted(MEMORY_OWNERS))
                .param("checkedBefore", checkedBefore.toOffsetDateTime())
                .query((resultSet, _) -> resultSet.getObject("user_id", UUID.class))
                .list();
    }

    /**
     * Owners who are removed, or have written no message of their own since {@code activeSince}.
     */
    public List<UUID> inactiveOwners(ZonedDateTime activeSince) {
        return jdbcClient.sql("""
                        select owners.user_id
                          from (%s) owners
                         where exists (select 1
                                         from user_profile profile
                                        where profile.user_id = owners.user_id
                                          and profile.removed is not null)
                            or not exists (select 1
                                             from chat
                                             join chat_message message on message.chat_id = chat.id
                                            where chat.user_id = owners.user_id
                                              and message.message_type = 'USER'
                                              and message.timestamp >= :activeSince)
                        """.formatted(MEMORY_OWNERS))
                .param("activeSince", activeSince.toOffsetDateTime())
                .query((resultSet, _) -> resultSet.getObject("user_id", UUID.class))
                .list();
    }

    /**
     * Records what Keycloak said about a live account. A username or email it no longer carries keeps
     * the stored value, as a login would.
     */
    public void recordFound(UUID userId, String username, String email) {
        jdbcClient.sql("""
                        insert into user_profile (user_id, username, email, updated, checked, removed)
                        values (:userId, :username, :email, now(), now(), null)
                        on conflict (user_id) do update
                           set username = coalesce(excluded.username, user_profile.username),
                               email = coalesce(excluded.email, user_profile.email),
                               updated = case
                                             when (coalesce(excluded.username, user_profile.username),
                                                   coalesce(excluded.email, user_profile.email))
                                                  is distinct from (user_profile.username, user_profile.email)
                                                 then now()
                                             else user_profile.updated
                                         end,
                               checked = now(),
                               removed = null
                        """)
                .param("userId", userId)
                .param("username", username)
                .param("email", email)
                .update();
    }

    public void recordRemoved(UUID userId) {
        jdbcClient.sql("""
                        insert into user_profile (user_id, updated, checked, removed)
                        values (:userId, now(), now(), now())
                        on conflict (user_id) do update
                           set checked = now(),
                               removed = coalesce(user_profile.removed, now())
                        """)
                .param("userId", userId)
                .update();
    }

    /**
     * Every memory, reflection cursor and interest row the user owns, in one transaction. Evidence
     * and an agent's seen items go by {@code on delete cascade}; news chats and their
     * {@code interest_update} rows stay, as conversation history does.
     *
     * @return the rows deleted, cascades not counted
     */
    @Transactional
    public int purgeMemoryData(UUID userId) {
        int deleted = 0;

        for (String table : MEMORY_TABLES) {
            deleted += jdbcClient.sql("delete from " + table + " where user_id = :userId")
                    .param("userId", userId)
                    .update();
        }

        return deleted;
    }
}
