package com.solesonic.repository.ingestion;

import com.solesonic.model.document.DocumentSource;
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
     * The asynchronous backlog {@code processQueued} drains: every document sitting at
     * {@code QUEUED}, whoever owns it.
     * <p>
     * There is deliberately no ownership filter. One used to exclude {@code CHAT} documents, resting
     * on the stated invariant that such a row is ingested inline and so "is never {@code QUEUED}",
     * which made the exclusion free. That invariant stopped holding the moment
     * {@code IngestedDocumentService.queueForChat} was added for
     * {@code POST /chats/{chatId}/documents}: it writes {@code QUEUED} against a conversation, and
     * the filter then dropped the row from the only query that would ever pick it up — so a document
     * uploaded straight to a chat sat at {@code QUEUED} forever and was never ingested.
     * <p>
     * {@code QUEUED} is the whole question this query asks. Which documents must not contend for the
     * ingest slot is a different question, asked by {@link #findInProgress} against
     * {@code documentSource}; the two coincided only for as long as {@code CHAT} scope implied
     * inline ingestion.
     * <p>
     * The join to {@code IngestedDocument} stays: it drops any status row whose document no longer
     * exists, which would otherwise stall the scheduler permanently.
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
                ORDER BY d.timestamp DESC
            """)
    List<StatusHistory> findQueued();

    /**
     * What {@code processQueued} treats as a reason to stand down this tick: a document being
     * ingested by the scheduler itself, and nothing else.
     * <p>
     * A document read from a chat attachment is indexed <em>inline</em>, on the turn it is attached
     * to, and holds one of these statuses for the several seconds that takes. Without an exclusion
     * every scheduler tick landing inside that window would skip the whole backlog, because this
     * query cannot otherwise tell an inline ingest from a queued upload — a burst of attachments
     * across concurrent conversations could keep the table continuously "busy" and delay unrelated
     * work that had nothing to do with any chat. Such rows are in {@code status_history} for cleanup
     * and observability parity, not to participate in scheduling.
     * <p>
     * The exclusion is keyed on {@code documentSource}, not on scope. {@link DocumentSource#CHAT} is
     * written only by {@code IngestedDocumentService.chatIngestedDocument} and means exactly "bytes
     * live on a {@code chat_attachment}, ingested inline" — which is the real question. Scope was
     * only ever a proxy for it, and a bad one: {@code queueForChat} writes a document that is
     * chat-owned but asynchronous, and the old scope filter excluded it from
     * {@link #findQueued} entirely (see that method).
     * <p>
     * A null {@code documentSource} counts as the scheduler's, which is the safe direction: it
     * blocks a tick rather than letting an unrecognised row ingest concurrently.
     * <p>
     * <em>Caveat:</em> this couples scheduler control to provenance. It is exact today. If an
     * ingestion path ever runs inline without being {@code CHAT}-sourced, give it an explicit
     * ingestion mode rather than adding a third proxy.
     * <p>
     * The join to {@code IngestedDocument} is what makes {@code documentSource} reachable, and it
     * also drops any status row whose document no longer exists. Such a row would otherwise stall
     * the scheduler permanently, having no ingestion left to finish and move it out of these
     * statuses; {@link #findQueued} joins the same way, so the two agree.
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
                  AND (
                        td.documentSource IS NULL
                     OR td.documentSource <> com.solesonic.model.document.DocumentSource.CHAT
                      )
                ORDER BY d.timestamp DESC
            """)
    List<StatusHistory> findInProgress();
}
