package com.solesonic.izzybot.repository.ollama;

import com.solesonic.izzybot.model.training.TrainingDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.solesonic.izzybot.model.training.TrainingDocument.CONFLUENCE_PAGE_ID;

public interface TrainingDocumentRepository extends JpaRepository<TrainingDocument, UUID> {

    @Query("""
                select new TrainingDocument(td.id, td.fileName, td.contentType)
                from TrainingDocument td
                order by td.created desc
            """)
    Optional<List<TrainingDocument>> findAllWithoutContent();

    @Query("""
        SELECT new TrainingDocument(td.id, td.fileName, td.contentType)
        FROM TrainingDocument td
        WHERE td.fileName = :fileName
        """)
    Optional<TrainingDocument> findByFileName(String fileName);

    @Query(value = """
        SELECT *
        FROM public.training_document td
        WHERE td.metadata->>'CONFLUENCE_PAGE_ID' = :CONFLUENCE_PAGE_ID
        """
        , nativeQuery = true)
    Optional<List<TrainingDocument>> findByConfluenceId(@Param(CONFLUENCE_PAGE_ID) String externalId);
}
