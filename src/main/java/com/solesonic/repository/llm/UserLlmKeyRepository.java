package com.solesonic.repository.llm;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code user_llm_key}. The key column is only ever read by {@link #findApiKey}, so no listing can
 * carry it by accident.
 */
@Repository
public class UserLlmKeyRepository {

    private static final String METADATA_COLUMNS = "user_id, key_hint, created, updated, updated_by";

    private final JdbcClient jdbcClient;

    public UserLlmKeyRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public record StoredLlmKey(UUID userId, String keyHint, ZonedDateTime created, ZonedDateTime updated, UUID updatedBy) {
    }

    public Optional<byte[]> findApiKey(UUID userId) {
        return jdbcClient.sql("select api_key from user_llm_key where user_id = :userId")
                .param("userId", userId)
                .query((resultSet, _) -> resultSet.getBytes("api_key"))
                .optional();
    }

    public Optional<StoredLlmKey> find(UUID userId) {
        return jdbcClient.sql("select " + METADATA_COLUMNS + " from user_llm_key where user_id = :userId")
                .param("userId", userId)
                .query((resultSet, _) -> storedLlmKey(resultSet))
                .optional();
    }

    public List<StoredLlmKey> findPage(int limit, long offset) {
        return jdbcClient.sql("select " + METADATA_COLUMNS + " from user_llm_key order by updated desc, user_id limit :limit offset :offset")
                .param("limit", limit)
                .param("offset", offset)
                .query((resultSet, _) -> storedLlmKey(resultSet))
                .list();
    }

    public long count() {
        return jdbcClient.sql("select count(*) from user_llm_key")
                .query(Long.class)
                .single();
    }

    /**
     * Replaces the user's key if they have one. {@code created} survives a replacement, so it says
     * when the user first got model access.
     */
    public void save(UUID userId, byte[] encryptedApiKey, String keyHint, UUID updatedBy) {
        jdbcClient.sql("""
                        insert into user_llm_key (user_id, api_key, key_hint, created, updated, updated_by)
                        values (:userId, :apiKey, :keyHint, now(), now(), :updatedBy)
                        on conflict (user_id) do update
                           set api_key = excluded.api_key,
                               key_hint = excluded.key_hint,
                               updated = excluded.updated,
                               updated_by = excluded.updated_by
                        """)
                .param("userId", userId)
                .param("apiKey", encryptedApiKey)
                .param("keyHint", keyHint)
                .param("updatedBy", updatedBy)
                .update();
    }

    /**
     * @return false when the user had no key
     */
    public boolean delete(UUID userId) {
        return jdbcClient.sql("delete from user_llm_key where user_id = :userId")
                .param("userId", userId)
                .update() > 0;
    }

    private static StoredLlmKey storedLlmKey(ResultSet resultSet) throws SQLException {
        return new StoredLlmKey(resultSet.getObject("user_id", UUID.class),
                resultSet.getString("key_hint"),
                resultSet.getObject("created", OffsetDateTime.class).toZonedDateTime(),
                resultSet.getObject("updated", OffsetDateTime.class).toZonedDateTime(),
                resultSet.getObject("updated_by", UUID.class));
    }
}
