package com.solesonic.service.rag;

import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentMetadata;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;

import java.util.List;

import static com.solesonic.service.etl.DocumentService.INGESTED_DOCUMENT_ID;

/**
 * A {@link DocumentPostProcessor} that logs the documents retrieved for a query without
 * altering them, so the retrieval step of the RAG cycle stays observable in application logs.
 */
@NullMarked
public class RetrievalLoggingPostProcessor implements DocumentPostProcessor {
    private static final Logger log = LoggerFactory.getLogger(RetrievalLoggingPostProcessor.class);

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        log.info("Retrieved {} documents for query \"{}\"", documents.size(), query.text());

        for (Document document : documents) {
            Object ingestedDocumentId = document.getMetadata().get(INGESTED_DOCUMENT_ID);
            Object distance = document.getMetadata().get(DocumentMetadata.DISTANCE.value());

            log.info("Retrieved document id={} ingestedDocumentId={} distance={}",
                    document.getId(), ingestedDocumentId, distance);
        }

        return documents;
    }
}
