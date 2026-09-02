package com.solesonic.repository.ingestion;

import com.solesonic.model.ingestion.DocumentStatus;
import com.solesonic.model.ingestion.StatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface StatusHistoryRepository extends JpaRepository<StatusHistory, UUID> {
    @Query("SELECT sh.documentStatus FROM StatusHistory sh WHERE sh.documentId = :documentId ORDER BY sh.timestamp DESC")
    List<DocumentStatus> findByDocumentId(@Param("documentId") UUID documentId);

    void deleteByDocumentId(UUID documentId);

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

    @Query("""
                SELECT d
                FROM
                    StatusHistory d
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
                ORDER BY d.timestamp DESC
            """)
    List<StatusHistory> findInProgress();
}
