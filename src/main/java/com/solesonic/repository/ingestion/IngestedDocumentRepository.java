package com.solesonic.repository.ingestion;

import com.solesonic.model.ingestion.IngestedDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.solesonic.model.ingestion.IngestedDocument.CONFLUENCE_PAGE_ID;
import static com.solesonic.model.ingestion.IngestedDocument.SOURCE_URI;

public interface IngestedDocumentRepository extends JpaRepository<IngestedDocument, UUID> {

    @Query("""
                select new IngestedDocument(td.id, td.fileName, td.contentType)
                from IngestedDocument td
                order by td.created desc
            """)
    Optional<List<IngestedDocument>> findAllWithoutContent();

    @Query("""
        SELECT new IngestedDocument(td.id, td.fileName, td.contentType)
        FROM IngestedDocument td
        WHERE td.fileName = :fileName
        """)
    Optional<IngestedDocument> findByFileName(String fileName);

    @Query(value = """
        SELECT *
        FROM public.ingested_document td
        WHERE td.metadata->>'CONFLUENCE_PAGE_ID' = :CONFLUENCE_PAGE_ID
        """
        , nativeQuery = true)
    Optional<List<IngestedDocument>> findByConfluenceId(@Param(CONFLUENCE_PAGE_ID) String externalId);

    @Query(value = """
        SELECT DISTINCT td.metadata->>'CONFLUENCE_PAGE_ID'
        FROM public.ingested_document td
        WHERE td.document_source = 'CONFLUENCE'
          AND td.metadata->>'CONFLUENCE_PAGE_ID' IS NOT NULL
        """
        , nativeQuery = true)
    List<String> findConfluencePageIds();

    @Query(value = """
        SELECT *
        FROM public.ingested_document td
        WHERE td.document_source = 'URI'
          AND td.metadata->>'SOURCE_URI' = :SOURCE_URI
        """
        , nativeQuery = true)
    Optional<List<IngestedDocument>> findBySourceUri(@Param(SOURCE_URI) String sourceUri);
}
