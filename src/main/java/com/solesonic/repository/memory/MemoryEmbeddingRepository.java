package com.solesonic.repository.memory;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * The embedding columns of {@code user_memory}, which {@link com.solesonic.model.memory.UserMemory}
 * deliberately does not map: JPA never reads or writes them, so no entity save can clobber a vector
 * written meanwhile, and the entity needs no vector type.
 * <p>
 * Like {@link UserMemoryRepository}, every read for retrieval is scoped by {@code userId}.
 */
@Repository
public class MemoryEmbeddingRepository {

    /**
     * Memories with no vector of {@code model} for their current content. Pending memories are
     * included so one is ready to be retrieved the moment it is activated.
     */
    private static final String STALE_CONDITION = """
            status in ('ACTIVE', 'PENDING')
               and (embedding is null
                    or embedding_model is distinct from :model
                    or embedding_hash is distinct from md5(content))
            """;

    private final JdbcClient jdbcClient;

    public MemoryEmbeddingRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public record StaleMemory(UUID id, UUID userId, String content) {
    }

    public record MemorySimilarity(UUID id, double similarity) {
    }

    public List<StaleMemory> findStale(String model, int limit) {
        return jdbcClient.sql("select id, user_id, content from user_memory where " + STALE_CONDITION + " order by updated limit :limit")
                .param("model", model)
                .param("limit", limit)
                .query(StaleMemory.class)
                .list();
    }

    /**
     * Across every user, for the admin status page only: how many live memories still wait for a
     * vector of {@code model}.
     */
    public long countStale(String model) {
        return jdbcClient.sql("select count(*) from user_memory where " + STALE_CONDITION)
                .param("model", model)
                .query(Long.class)
                .single();
    }

    public List<StaleMemory> findStale(UUID userId, String model, int limit) {
        return jdbcClient.sql("select id, user_id, content from user_memory where user_id = :userId and " + STALE_CONDITION
                        + " order by updated limit :limit")
                .param("userId", userId)
                .param("model", model)
                .param("limit", limit)
                .query(StaleMemory.class)
                .list();
    }

    /**
     * Writes the vector only if the content is still what was embedded, so an edit made while the
     * embedding call was in flight is left stale and embedded again rather than given the old vector.
     *
     * @return false when the memory changed or went meanwhile
     */
    public boolean saveEmbedding(UUID id, UUID userId, String embeddedContent, float[] embedding, String model) {
        int updated = jdbcClient.sql("""
                        update user_memory
                           set embedding = cast(:embedding as vector),
                               embedding_model = :model,
                               embedding_hash = md5(content)
                         where id = :id
                           and user_id = :userId
                           and content = :content
                        """)
                .param("embedding", vectorLiteral(embedding))
                .param("model", model)
                .param("id", id)
                .param("userId", userId)
                .param("content", embeddedContent)
                .update();

        return updated > 0;
    }

    /**
     * The user's active memories other than standing instructions, nearest to {@code query} first,
     * with their cosine similarity. A memory whose vector is stale or from another model is skipped
     * rather than compared.
     */
    public List<MemorySimilarity> findNearest(UUID userId, float[] query, String model, int limit) {
        return jdbcClient.sql("""
                        select id, 1 - (embedding <=> cast(:query as vector)) as similarity
                          from user_memory
                         where user_id = :userId
                           and status = 'ACTIVE'
                           and kind <> 'INSTRUCTION'
                           and embedding_model = :model
                           and embedding_hash = md5(content)
                         order by embedding <=> cast(:query as vector)
                         limit :limit
                        """)
                .param("query", vectorLiteral(query))
                .param("userId", userId)
                .param("model", model)
                .param("limit", limit)
                .query(MemorySimilarity.class)
                .list();
    }

    /**
     * The user's live memories of every kind, active or pending, nearest to {@code query} first, for
     * reflection to compare what it reads and proposes against.
     */
    public List<MemorySimilarity> findNearestLive(UUID userId, float[] query, String model, int limit) {
        return jdbcClient.sql("""
                        select id, 1 - (embedding <=> cast(:query as vector)) as similarity
                          from user_memory
                         where user_id = :userId
                           and status in ('ACTIVE', 'PENDING')
                           and embedding_model = :model
                           and embedding_hash = md5(content)
                         order by embedding <=> cast(:query as vector)
                         limit :limit
                        """)
                .param("query", vectorLiteral(query))
                .param("userId", userId)
                .param("model", model)
                .param("limit", limit)
                .query(MemorySimilarity.class)
                .list();
    }

    /**
     * pgvector's text form, {@code [x,y,...]}, which it parses with {@code strtof} and so also
     * accepts the exponent notation {@link Float#toString} can produce.
     */
    static String vectorLiteral(float[] vector) {
        StringBuilder literal = new StringBuilder(vector.length * 12).append('[');

        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                literal.append(',');
            }

            literal.append(vector[index]);
        }

        return literal.append(']').toString();
    }
}
