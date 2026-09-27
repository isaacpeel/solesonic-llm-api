package com.solesonic.repository.ingestion;

import com.solesonic.model.ingestion.IngestedDocumentContent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface IngestedDocumentContentRepository extends JpaRepository<IngestedDocumentContent, UUID> {

    /**
     * The bytes alone, without materialising the entity around them.
     * <p>
     * The ingest path wants exactly this and nothing else, and asking for it directly keeps the one
     * expensive read in the system explicit — the same stance
     * {@code ChatAttachmentRepository.findFileDataById} takes, and for the same reason: a lazy
     * {@code byte[]} attribute is only honoured with bytecode enhancement and throws outside an open
     * session, so the reliable way to not load bytes is to not select them.
     */
    @Query("""
            select content.data
              from IngestedDocumentContent content
             where content.ingestedDocumentId = :ingestedDocumentId
            """)
    Optional<byte[]> findDataByIngestedDocumentId(UUID ingestedDocumentId);

    boolean existsByIngestedDocumentId(UUID ingestedDocumentId);
}
