package com.solesonic.service.etl;

import com.solesonic.model.document.DocumentSource;
import com.solesonic.model.rag.RetrievalScope;
import com.solesonic.model.ingestion.DocumentStatus;
import com.solesonic.model.ingestion.IngestedDocument;
import com.solesonic.service.ingestion.IngestedDocumentService;
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
    public static final String INGESTED_DOCUMENT_ID = "INGESTED_DOCUMENT_ID";
    private final VectorStoreService vectorStoreService;
    private final EtlService etlService;
    private final IngestedDocumentService ingestedDocumentService;
    private final UriContentFetcher uriContentFetcher;

    public DocumentService(VectorStoreService vectorStoreService,
                           EtlService etlService,
                           IngestedDocumentService ingestedDocumentService,
                           UriContentFetcher uriContentFetcher) {
        this.vectorStoreService = vectorStoreService;
        this.etlService = etlService;
        this.ingestedDocumentService = ingestedDocumentService;
        this.uriContentFetcher = uriContentFetcher;
    }

    /**
     * Stores the given resource to the vector store
     */
    public void resourceToVectorStore(UUID ingestedDocumentId) {
        log.info("Saving resource to the vector store.");

        IngestedDocument ingestedDocument = ingestedDocumentService.get(ingestedDocumentId);

        if (ingestedDocument.getDocumentSource() == DocumentSource.URI) {
            fetchUriContent(ingestedDocument);
        }

        String contentType = ingestedDocument.getContentType();

        byte[] fileContent = ingestedDocument.getFileData();

        ByteArrayResource resource = new ByteArrayResource(fileContent) {
            @Override
            public String getFilename() {
                return ingestedDocument.getFileName();
            }
        };

        assert contentType != null;

        List<Document> documents = read(resource, contentType);

        documents = etlService.prepare(documents, ingestedDocument);

        for(Document document : documents) {
            Map<String, Object> metadata = document.getMetadata();
            metadata.put(INGESTED_DOCUMENT_ID, ingestedDocument.getId());
            scope(metadata, ingestedDocument);
            vectorStoreService.save(List.of(document));
        }

        ingestedDocumentService.update(ingestedDocument, DocumentStatus.COMPLETED);

    }

    /**
     * Stamps the scope every retrieval filter reads. A document with no scope recorded predates
     * scoping and is shared, which is what it has always effectively been — the same default the
     * backfill migration applied to chunks already in the store.
     * <p>
     * The ids go in as strings: a filter expression compares against a JSON string, so a UUID
     * written as anything else would never match.
     */
    private static void scope(Map<String, Object> metadata, IngestedDocument ingestedDocument) {
        RetrievalScope scope = ingestedDocument.getScope() == null
                ? RetrievalScope.GLOBAL
                : ingestedDocument.getScope();

        metadata.put(SCOPE, scope.name());

        if (scope == RetrievalScope.USER && ingestedDocument.getUserId() != null) {
            metadata.put(USER_ID, ingestedDocument.getUserId().toString());
        }
    }

    private void fetchUriContent(IngestedDocument ingestedDocument) {
        Object sourceUri = ingestedDocument.getMetadata().get(IngestedDocument.SOURCE_URI);
        assert sourceUri != null;

        UriContentFetcher.FetchedContent fetchedContent = uriContentFetcher.fetch(sourceUri.toString());

        ingestedDocument.setFileData(fetchedContent.data());
        ingestedDocument.setContentType(fetchedContent.contentType());
        ingestedDocument.getMetadata().put(IngestedDocument.FILE_SIZE_BYTES, fetchedContent.data().length);
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
