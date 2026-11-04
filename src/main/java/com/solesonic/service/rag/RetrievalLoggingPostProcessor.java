package com.solesonic.service.rag;

import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentMetadata;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;

import java.util.List;

import static com.solesonic.model.rag.RetrievalMetadata.ENTITLEMENTS;
import static com.solesonic.service.etl.DocumentService.INGESTED_DOCUMENT_ID;

/**
 * A {@link DocumentPostProcessor} that logs what was retrieved without altering it, so retrieval can
 * be tuned from the logs.
 * <p>
 * Logs counts, ids, distances and audiences — <strong>never the query text</strong>. The query is the
 * user's own message; logging it would copy chat content into {@code /var/log} and from there into
 * the log agent's database. At debug, so it costs nothing unless someone is tuning.
 */
@NullMarked
public class RetrievalLoggingPostProcessor implements DocumentPostProcessor {
    private static final Logger log = LoggerFactory.getLogger(RetrievalLoggingPostProcessor.class);

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        if (!log.isDebugEnabled()) {
            return documents;
        }

        log.debug("Retrieved {} document(s) for a {}-character query", documents.size(), query.text().length());

        for (Document document : documents) {
            log.debug("Retrieved chunk id={} ingestedDocumentId={} distance={} entitlements={}",
                    document.getId(),
                    document.getMetadata().get(INGESTED_DOCUMENT_ID),
                    document.getMetadata().get(DocumentMetadata.DISTANCE.value()),
                    document.getMetadata().get(ENTITLEMENTS));
        }

        return documents;
    }
}
