package com.solesonic.repository.ollama;

import com.solesonic.model.training.TrainingDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.solesonic.model.training.TrainingDocument.CONFLUENCE_PAGE_ID;
import static com.solesonic.model.training.TrainingDocument.SOURCE_URI;

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

    @Query(value = """
        SELECT DISTINCT td.metadata->>'CONFLUENCE_PAGE_ID'
        FROM public.training_document td
        WHERE td.document_source = 'CONFLUENCE'
          AND td.metadata->>'CONFLUENCE_PAGE_ID' IS NOT NULL
        """
        , nativeQuery = true)
    List<String> findConfluencePageIds();

    @Query(value = """
        SELECT *
        FROM public.training_document td
        WHERE td.document_source = 'URI'
          AND td.metadata->>'SOURCE_URI' = :SOURCE_URI
        """
        , nativeQuery = true)
    Optional<List<TrainingDocument>> findBySourceUri(@Param(SOURCE_URI) String sourceUri);
}
