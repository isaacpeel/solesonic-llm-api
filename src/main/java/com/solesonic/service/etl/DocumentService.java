package com.solesonic.service.etl;

import com.solesonic.model.document.DocumentSource;
import com.solesonic.model.rag.RetrievalScope;
import com.solesonic.model.training.DocumentStatus;
import com.solesonic.model.training.TrainingDocument;
import com.solesonic.service.rag.TrainingDocumentService;
import com.solesonic.service.rag.VectorStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.solesonic.model.rag.RetrievalMetadata.SCOPE;
import static com.solesonic.model.rag.RetrievalMetadata.USER_ID;
import static org.springframework.http.MediaType.*;

@Service
public class DocumentService {
    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    public static final String TRAINING_DOCUMENT_ID = "TRAINING_DOCUMENT_ID";
    private final VectorStoreService vectorStoreService;
    private final EtlService etlService;
    private final TrainingDocumentService trainingDocumentService;
    private final UriContentFetcher uriContentFetcher;

    public DocumentService(VectorStoreService vectorStoreService,
                           EtlService etlService,
                           TrainingDocumentService trainingDocumentService,
                           UriContentFetcher uriContentFetcher) {
        this.vectorStoreService = vectorStoreService;
        this.etlService = etlService;
        this.trainingDocumentService = trainingDocumentService;
        this.uriContentFetcher = uriContentFetcher;
    }

    /**
     * Stores the given resource to the vector store
     */
    public void resourceToVectorStore(UUID trainingDocumentId) {
        log.info("Saving resource to the vector store.");

        TrainingDocument trainingDocument = trainingDocumentService.get(trainingDocumentId);

        if (trainingDocument.getDocumentSource() == DocumentSource.URI) {
            fetchUriContent(trainingDocument);
        }

        String contentType = trainingDocument.getContentType();

        byte[] fileContent = trainingDocument.getFileData();

        ByteArrayResource resource = new ByteArrayResource(fileContent) {
            @Override
            public String getFilename() {
                return trainingDocument.getFileName();
            }
        };

        assert contentType != null;

        List<Document> documents = read(resource, contentType);

        documents = etlService.prepare(documents, trainingDocument);

        for(Document document : documents) {
            Map<String, Object> metadata = document.getMetadata();
            metadata.put(TRAINING_DOCUMENT_ID, trainingDocument.getId());
            scope(metadata, trainingDocument);
            vectorStoreService.save(List.of(document));
        }

        trainingDocumentService.update(trainingDocument, DocumentStatus.COMPLETED);

    }

    /**
     * Stamps the scope every retrieval filter reads. A document with no scope recorded predates
     * scoping and is shared, which is what it has always effectively been — the same default the
     * backfill migration applied to chunks already in the store.
     * <p>
     * The ids go in as strings: a filter expression compares against a JSON string, so a UUID
     * written as anything else would never match.
     */
    private static void scope(Map<String, Object> metadata, TrainingDocument trainingDocument) {
        RetrievalScope scope = trainingDocument.getScope() == null
                ? RetrievalScope.GLOBAL
                : trainingDocument.getScope();

        metadata.put(SCOPE, scope.name());

        if (scope == RetrievalScope.USER && trainingDocument.getUserId() != null) {
            metadata.put(USER_ID, trainingDocument.getUserId().toString());
        }
    }

    private void fetchUriContent(TrainingDocument trainingDocument) {
        Object sourceUri = trainingDocument.getMetadata().get(TrainingDocument.SOURCE_URI);
        assert sourceUri != null;

        UriContentFetcher.FetchedContent fetchedContent = uriContentFetcher.fetch(sourceUri.toString());

        trainingDocument.setFileData(fetchedContent.data());
        trainingDocument.setContentType(fetchedContent.contentType());
        trainingDocument.getMetadata().put(TrainingDocument.FILE_SIZE_BYTES, fetchedContent.data().length);
    }

    /**
     * Parses a resource into documents with the reader its content type calls for.
     * <p>
     * Tika is the default rather than the plain text reader: it is what handles the binary office
     * formats, and running {@code TextReader} over one of those yields mojibake rather than a
     * failure — text that embeds cleanly and retrieves as nonsense.
     */
    public List<Document> read(Resource resource, String contentType) {
        return switch (contentType) {
            case APPLICATION_PDF_VALUE -> fromPdf(resource);
            case TEXT_PLAIN_VALUE -> fromPlain(resource);
            default -> fromTika(resource);
        };
    }

    public List<Document> fromTika(Resource resource) {
        TikaDocumentReader tikaDocumentReader = new TikaDocumentReader(resource);

        return tikaDocumentReader.read();
    }

    public List<Document> fromPlain(Resource textResource) {
        TextReader textReader = new TextReader(textResource);

        return textReader.read();
    }

    public List<Document> fromPdf(Resource pdfResource) {
        ExtractedTextFormatter extractedTextFormatter = ExtractedTextFormatter.builder()
                .build();

        var config = PdfDocumentReaderConfig.builder()
                .withPageExtractedTextFormatter(extractedTextFormatter)
                .withPagesPerDocument(5)
                .build();

        var pdfReader = new PagePdfDocumentReader(pdfResource, config);

        return pdfReader.get();
    }
}
