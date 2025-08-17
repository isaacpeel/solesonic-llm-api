package com.solesonic.repository.ollama;

import com.solesonic.model.training.DocumentStatus;
import com.solesonic.model.training.StatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface StatusHistoryRepository extends JpaRepository<StatusHistory, UUID> {
    @Query("SELECT sh.documentStatus FROM StatusHistory sh WHERE sh.documentId = :documentId ORDER BY sh.timestamp DESC")
    List<DocumentStatus> findByDocumentId(@Param("documentId") UUID documentId);

    @Query("""
                SELECT d
                FROM
                    StatusHistory d,
                    TrainingDocument td
                WHERE d.documentStatus = com.solesonic.model.training.DocumentStatus.QUEUED
                  AND NOT EXISTS (
                    SELECT 1
                    FROM StatusHistory d2
                    WHERE d2.documentId = d.documentId
                      AND d2.documentStatus IN (
                          com.solesonic.model.training.DocumentStatus.IN_PROGRESS,
                          com.solesonic.model.training.DocumentStatus.COMPLETED,
                          com.solesonic.model.training.DocumentStatus.PREPARING,
                          com.solesonic.model.training.DocumentStatus.KEYWORD_ENRICHING,
                          com.solesonic.model.training.DocumentStatus.METADATA_ENRICHING,
                          com.solesonic.model.training.DocumentStatus.TOKEN_SPLITTING,
                          com.solesonic.model.training.DocumentStatus.FAILED
                      )
                  )
                AND d.documentId = td.id
                ORDER BY d.timestamp DESC
            """)
    List<StatusHistory> findQueued();

    @Query("""
                SELECT d
                FROM
                    StatusHistory d
                WHERE d.documentStatus = com.solesonic.model.training.DocumentStatus.IN_PROGRESS
                  AND NOT EXISTS (
                    SELECT 1
                    FROM StatusHistory d2
                    WHERE d2.documentId = d.documentId
                      AND d2.documentStatus IN (
                          com.solesonic.model.training.DocumentStatus.COMPLETED,
                          com.solesonic.model.training.DocumentStatus.QUEUED,
                          com.solesonic.model.training.DocumentStatus.PREPARING,
                          com.solesonic.model.training.DocumentStatus.KEYWORD_ENRICHING,
                          com.solesonic.model.training.DocumentStatus.METADATA_ENRICHING,
                          com.solesonic.model.training.DocumentStatus.TOKEN_SPLITTING,
                          com.solesonic.model.training.DocumentStatus.FAILED
                      )
                  )
                ORDER BY d.timestamp DESC
            """)
    List<StatusHistory> findInProgress();
}
