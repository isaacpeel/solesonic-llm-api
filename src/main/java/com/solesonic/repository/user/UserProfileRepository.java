package com.solesonic.repository.user;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class UserProfileRepository {

    private final JdbcClient jdbcClient;

    public UserProfileRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Rewrites the row only when the name is new or changed.
     */
    public void saveDisplayName(UUID userId, String displayName) {
        jdbcClient.sql("""
                        insert into user_profile (user_id, display_name, updated)
                        values (:userId, :displayName, now())
                        on conflict (user_id) do update
                           set display_name = excluded.display_name,
                               updated = excluded.updated
                         where user_profile.display_name is distinct from excluded.display_name
                        """)
                .param("userId", userId)
                .param("displayName", displayName)
                .update();
    }

    public Optional<String> findDisplayName(UUID userId) {
        return jdbcClient.sql("select display_name from user_profile where user_id = :userId")
                .param("userId", userId)
                .query(String.class)
                .optional();
    }
}
