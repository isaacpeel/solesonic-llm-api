package com.solesonic.repository.user;

import com.solesonic.model.user.UserProfile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class UserProfileRepository {

    private final JdbcClient jdbcClient;

    public UserProfileRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Rewrites the row only when something changed. A field the token did not carry keeps its stored
     * value rather than being cleared.
     */
    public void save(UserProfile userProfile) {
        jdbcClient.sql("""
                        insert into user_profile (user_id, username, email, display_name, updated)
                        values (:userId, :username, :email, :displayName, now())
                        on conflict (user_id) do update
                           set username = coalesce(excluded.username, user_profile.username),
                               email = coalesce(excluded.email, user_profile.email),
                               display_name = coalesce(excluded.display_name, user_profile.display_name),
                               updated = excluded.updated
                         where (user_profile.username, user_profile.email, user_profile.display_name)
                               is distinct from
                               (coalesce(excluded.username, user_profile.username),
                                coalesce(excluded.email, user_profile.email),
                                coalesce(excluded.display_name, user_profile.display_name))
                        """)
                .param("userId", userProfile.userId())
                .param("username", userProfile.username())
                .param("email", userProfile.email())
                .param("displayName", userProfile.displayName())
                .update();
    }

    public Optional<UserProfile> findById(UUID userId) {
        return jdbcClient.sql("select user_id, username, email, display_name from user_profile where user_id = :userId")
                .param("userId", userId)
                .query((resultSet, _) -> profile(resultSet))
                .optional();
    }

    public List<UserProfile> findAllById(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }

        return jdbcClient.sql("select user_id, username, email, display_name from user_profile where user_id in (:userIds)")
                .param("userIds", userIds)
                .query((resultSet, _) -> profile(resultSet))
                .list();
    }

    /**
     * Case-insensitive. A username can outlive its account — deleted and created again under a new
     * id — so the most recently seen holder wins.
     */
    public Optional<UUID> findUserIdByUsername(String username) {
        return jdbcClient.sql("""
                        select user_id
                          from user_profile
                         where lower(username) = lower(:username)
                         order by updated desc
                         limit 1
                        """)
                .param("username", username)
                .query((resultSet, _) -> resultSet.getObject("user_id", UUID.class))
                .optional();
    }

    private static UserProfile profile(ResultSet resultSet) throws SQLException {
        return new UserProfile(resultSet.getObject("user_id", UUID.class),
                resultSet.getString("username"),
                resultSet.getString("email"),
                resultSet.getString("display_name"));
    }
}
