package com.solesonic.repository.memory;

import com.solesonic.model.memory.MemoryStatus;
import com.solesonic.model.memory.ReflectionStatus;
import com.solesonic.model.memory.UserMemoryOverview;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Counts across every user's memories and reflection rows, for the admin status page only. Nothing
 * here reads memory content.
 */
@Repository
public class MemoryAdminRepository {

    private final JdbcClient jdbcClient;

    public MemoryAdminRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Map<MemoryStatus, Long> countMemoriesByStatus() {
        Map<MemoryStatus, Long> counts = new EnumMap<>(MemoryStatus.class);

        for (MemoryStatus status : MemoryStatus.values()) {
            counts.put(status, 0L);
        }

        jdbcClient.sql("select status, count(*) as total from user_memory group by status")
                .query((resultSet, _) -> Map.entry(MemoryStatus.valueOf(resultSet.getString("status")), resultSet.getLong("total")))
                .list()
                .forEach(entry -> counts.put(entry.getKey(), entry.getValue()));

        return counts;
    }

    public Map<ReflectionStatus, Long> countReflectionsByStatus() {
        Map<ReflectionStatus, Long> counts = new EnumMap<>(ReflectionStatus.class);

        for (ReflectionStatus status : ReflectionStatus.values()) {
            counts.put(status, 0L);
        }

        jdbcClient.sql("select status, count(*) as total from chat_memory_reflection group by status")
                .query((resultSet, _) -> Map.entry(ReflectionStatus.valueOf(resultSet.getString("status")), resultSet.getLong("total")))
                .list()
                .forEach(entry -> counts.put(entry.getKey(), entry.getValue()));

        return counts;
    }

    /**
     * Conversations with at least one message newer than what reflection has read.
     */
    public long countAwaitingReflection() {
        return jdbcClient.sql("""
                        select count(*)
                          from chat_memory_reflection reflection
                         where exists (select 1
                                         from chat_message message
                                        where message.chat_id = reflection.chat_id
                                          and message.timestamp > reflection.reflected_through)
                        """)
                .query(Long.class)
                .single();
    }

    /**
     * Conversations the next reflection run will seed: the same condition as
     * {@link ChatMemoryReflectionRepository#seedMissing()}.
     */
    public long countUnseededChats() {
        return jdbcClient.sql("""
                        select count(*)
                          from chat
                         where chat.user_id is not null
                           and not exists (select 1 from chat_memory_reflection reflection where reflection.chat_id = chat.id)
                           and exists (select 1 from chat_message message where message.chat_id = chat.id)
                        """)
                .query(Long.class)
                .single();
    }

    public long countCompletedSince(ZonedDateTime since) {
        return jdbcClient.sql("select count(*) from chat_memory_reflection where status = 'COMPLETED' and updated >= :since")
                .param("since", since.toOffsetDateTime())
                .query(Long.class)
                .single();
    }

    public ZonedDateTime lastCompleted() {
        return jdbcClient.sql("select max(updated) as last_completed from chat_memory_reflection where status = 'COMPLETED'")
                .query((resultSet, _) -> zoned(resultSet, "last_completed"))
                .single();
    }

    /**
     * Live memories whose vector is current for {@code model}: the complement of the embedding
     * task's stale condition.
     */
    public long countEmbedded(String model) {
        return jdbcClient.sql("""
                        select count(*)
                          from user_memory
                         where status in ('ACTIVE', 'PENDING')
                           and embedding is not null
                           and embedding_model = :model
                           and embedding_hash = md5(content)
                        """)
                .param("model", model)
                .query(Long.class)
                .single();
    }

    public List<UserMemoryOverview> userOverviews() {
        return jdbcClient.sql("""
                        with memories as (
                            select user_id,
                                   count(*) filter (where status = 'ACTIVE') as active_memories,
                                   count(*) filter (where status = 'PENDING') as pending_memories,
                                   count(*) filter (where status = 'SUPERSEDED') as superseded_memories
                              from user_memory
                             group by user_id),
                        reflections as (
                            select user_id,
                                   count(*) filter (where status = 'PENDING') as pending_chats,
                                   count(*) filter (where status = 'IN_PROGRESS') as in_progress_chats,
                                   count(*) filter (where status = 'COMPLETED') as completed_chats,
                                   count(*) filter (where status = 'FAILED') as failed_chats,
                                   max(updated) filter (where status = 'COMPLETED') as last_reflected
                              from chat_memory_reflection
                             group by user_id)
                        users as (
                            select coalesce(memories.user_id, reflections.user_id) as user_id,
                                   coalesce(memories.active_memories, 0) as active_memories,
                                   coalesce(memories.pending_memories, 0) as pending_memories,
                                   coalesce(memories.superseded_memories, 0) as superseded_memories,
                                   coalesce(reflections.pending_chats, 0) as pending_chats,
                                   coalesce(reflections.in_progress_chats, 0) as in_progress_chats,
                                   coalesce(reflections.completed_chats, 0) as completed_chats,
                                   coalesce(reflections.failed_chats, 0) as failed_chats,
                                   reflections.last_reflected
                              from memories
                              full outer join reflections on reflections.user_id = memories.user_id)
                        select users.*, profile.username
                          from users
                          left join user_profile profile on profile.user_id = users.user_id
                         order by users.user_id
                        """)
                .query((resultSet, _) -> new UserMemoryOverview(resultSet.getObject("user_id", UUID.class),
                        resultSet.getString("username"),
                        resultSet.getLong("active_memories"),
                        resultSet.getLong("pending_memories"),
                        resultSet.getLong("superseded_memories"),
                        resultSet.getLong("pending_chats"),
                        resultSet.getLong("in_progress_chats"),
                        resultSet.getLong("completed_chats"),
                        resultSet.getLong("failed_chats"),
                        zoned(resultSet, "last_reflected")))
                .list();
    }

    /**
     * The driver reads {@code timestamptz} as an {@link OffsetDateTime}, never a {@link ZonedDateTime}.
     */
    private static ZonedDateTime zoned(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toZonedDateTime();
    }
}
