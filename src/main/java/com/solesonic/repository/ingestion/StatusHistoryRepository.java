package com.solesonic.repository.ingestion;

import com.solesonic.model.ingestion.DocumentStatus;
import com.solesonic.model.ingestion.DocumentStatusEntry;
import com.solesonic.model.ingestion.StatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface StatusHistoryRepository extends JpaRepository<StatusHistory, UUID> {
    @Query("SELECT sh.documentStatus FROM StatusHistory sh WHERE sh.documentId = :documentId ORDER BY sh.timestamp DESC")
    List<DocumentStatus> findByDocumentId(@Param("documentId") UUID documentId);

    void deleteByDocumentId(UUID documentId);

    /**
     * The latest status of each of the given documents, in one query.
     * <p>
     * {@link #findByDocumentId} answers the same question for a single document, and calling it per
     * row is what listing used to do. A page of twenty documents is twenty round trips that this
     * replaces with one.
     * <p>
     * The {@code not exists} clause picks the newest row per document the same way
     * {@link #findQueued} does. Two status rows sharing a timestamp would both come back; the caller
     * keeps the first, which is the same arbitrary choice {@code findByDocumentId} makes today.
     */
    @Query("""
                SELECT new com.solesonic.model.ingestion.DocumentStatusEntry(sh.documentId, sh.documentStatus)
                FROM StatusHistory sh
                WHERE sh.documentId IN :documentIds
                  AND NOT EXISTS (
                    SELECT 1
                    FROM StatusHistory later
                    WHERE later.documentId = sh.documentId
                      AND later.timestamp > sh.timestamp
                  )
            """)
    List<DocumentStatusEntry> findLatestStatuses(@Param("documentIds") Collection<UUID> documentIds);

    /**
     * The asynchronous backlog {@code processQueued} drains, which is {@code GLOBAL} and {@code USER}
     * documents only.
     * <p>
     * A {@code CHAT} document is ingested inline on the turn it is attached to and opens its row
     * already {@code IN_PROGRESS}, so it is never {@code QUEUED} and this exclusion should never
     * change an answer. It is stated anyway rather than left resting on that invariant, and so that
     * this query and {@link #findInProgress} agree on what the scheduler's world consists of.
     * <p>
     * The scope list is affirmative rather than {@code <> CHAT} on purpose: a scope added later is
     * left visibly stuck at {@code QUEUED} until someone decides whether the scheduler owns it,
     * which is a louder failure than silently reintroducing the contention this filter removes.
     */
    @Query("""
                SELECT d
                FROM
                    StatusHistory d,
                    IngestedDocument td
                WHERE d.documentStatus = com.solesonic.model.ingestion.DocumentStatus.QUEUED
                  AND NOT EXISTS (
                    SELECT 1
                    FROM StatusHistory d2
                    WHERE d2.documentId = d.documentId
                      AND d2.timestamp > d.timestamp
                  )
                  AND d.documentId = td.id
                  AND td.scope IN (
                          com.solesonic.model.rag.RetrievalScope.GLOBAL,
                          com.solesonic.model.rag.RetrievalScope.USER
                      )
                ORDER BY d.timestamp DESC
            """)
    List<StatusHistory> findQueued();

    /**
     * What {@code processQueued} treats as a reason to stand down this tick, which is a
     * {@code GLOBAL} or {@code USER} document mid-ingest and nothing else.
     * <p>
     * A {@code CHAT} document is indexed inline on the turn it is attached to, and holds one of
     * these statuses for the several seconds that takes. Without the scope filter every scheduler
     * tick landing inside that window would skip the whole backlog, because this query cannot
     * otherwise tell a chat attachment from a queued upload — a burst of attachments across
     * concurrent conversations could keep the table continuously "busy" and delay unrelated work
     * that had nothing to do with any chat. Chat rows are in {@code status_history} for cleanup and
     * observability parity, not to participate in scheduling.
     * <p>
     * The join to {@code IngestedDocument} is what makes the scope reachable, and it also drops any
     * status row whose document no longer exists. Such a row would otherwise stall the scheduler
     * permanently, having no ingestion left to finish and move it out of these statuses;
     * {@link #findQueued} has always joined this way, so the two now agree.
     */
    @Query("""
                SELECT d
                FROM
                    StatusHistory d,
                    IngestedDocument td
                WHERE d.documentStatus IN (
                          com.solesonic.model.ingestion.DocumentStatus.IN_PROGRESS,
                          com.solesonic.model.ingestion.DocumentStatus.PREPARING,
                          com.solesonic.model.ingestion.DocumentStatus.TOKEN_SPLITTING,
                          com.solesonic.model.ingestion.DocumentStatus.KEYWORD_ENRICHING,
                          com.solesonic.model.ingestion.DocumentStatus.METADATA_ENRICHING
                      )
                  AND NOT EXISTS (
                    SELECT 1
                    FROM StatusHistory d2
                    WHERE d2.documentId = d.documentId
                      AND d2.timestamp > d.timestamp
                  )
                  AND d.documentId = td.id
                  AND td.scope IN (
                          com.solesonic.model.rag.RetrievalScope.GLOBAL,
                          com.solesonic.model.rag.RetrievalScope.USER
                      )
                ORDER BY d.timestamp DESC
            """)
    List<StatusHistory> findInProgress();
}
