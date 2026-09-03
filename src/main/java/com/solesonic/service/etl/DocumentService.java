package com.solesonic.service.etl;

import com.solesonic.exception.rag.DocumentReadException;
import com.solesonic.model.chat.attachment.ChatAttachment;
import com.solesonic.model.document.DocumentSource;
import com.solesonic.model.rag.RetrievalScope;
import com.solesonic.model.ingestion.DocumentStatus;
import com.solesonic.model.ingestion.IngestedDocument;
import com.solesonic.repository.chat.ChatAttachmentRepository;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.solesonic.model.rag.RetrievalMetadata.CHAT_ATTACHMENT_ID;
import static com.solesonic.model.rag.RetrievalMetadata.CHAT_ID;
import static com.solesonic.model.rag.RetrievalMetadata.FILE_NAME;
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
    private final ChatAttachmentRepository chatAttachmentRepository;

    public DocumentService(VectorStoreService vectorStoreService,
                           EtlService etlService,
                           IngestedDocumentService ingestedDocumentService,
                           UriContentFetcher uriContentFetcher,
                           ChatAttachmentRepository chatAttachmentRepository) {
        this.vectorStoreService = vectorStoreService;
        this.etlService = etlService;
        this.ingestedDocumentService = ingestedDocumentService;
        this.uriContentFetcher = uriContentFetcher;
        this.chatAttachmentRepository = chatAttachmentRepository;
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

        if (ingestedDocument.getDocumentSource() == DocumentSource.CHAT) {
            loadChatAttachmentContent(ingestedDocument);
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

        RetrievalScope scope = scope(ingestedDocument);
        Map<String, Object> scopeMetadata = scopeMetadata(ingestedDocument, scope);

        for (Document document : documents) {
            scope(document.getMetadata(), scope, scopeMetadata);
            vectorStoreService.save(List.of(document));
        }

        ingestedDocumentService.update(ingestedDocument, DocumentStatus.COMPLETED);
    }

    /**
     * Reads, prepares, scopes and embeds a resource whose bytes live somewhere other than its own
     * {@link IngestedDocument} row — today that is a chat attachment, whose bytes stay on the
     * {@code chat_attachment} row they were uploaded to rather than being copied here.
     * <p>
     * Everything else is the queued path above: the same status-tracked
     * {@link EtlService#prepare(List, IngestedDocument)}, so a chat document's
     * {@code PREPARING}/{@code TOKEN_SPLITTING}/{@code KEYWORD_ENRICHING}/{@code METADATA_ENRICHING}
     * transitions reach {@code status_history} exactly as a {@code GLOBAL} or {@code USER}
     * document's do, and the same scope stamp, so its chunks carry {@link #INGESTED_DOCUMENT_ID}
     * like every other scope's.
     * <p>
     * Once the row is loaded, any failure marks it {@link DocumentStatus#FAILED} before the
     * exception leaves — a row stranded on {@code IN_PROGRESS} forever would be worse than the no
     * row at all this path used to leave behind. Failing to load it in the first place is the one
     * case that cannot be recorded on it, and needs no handling: either the row is already gone, or
     * the database is unreachable and the {@code FAILED} write would fail too.
     * <p>
     * The caller tells {@link DocumentReadException} apart from everything else, so the type is
     * rethrown untouched.
     *
     * @return the number of chunks written to the vector store
     * @throws DocumentReadException if no readable text could be extracted from the resource
     */
    public int resourceToVectorStore(Resource resource, UUID ingestedDocumentId) {
        log.info("Saving attached resource to the vector store.");

        IngestedDocument ingestedDocument = ingestedDocumentService.get(ingestedDocumentId);

        try {
            return prepareAndSave(resource, ingestedDocument);
        } catch (RuntimeException runtimeException) {
            ingestedDocumentService.update(ingestedDocument, DocumentStatus.FAILED);

            throw runtimeException;
        }
    }

    private int prepareAndSave(Resource resource, IngestedDocument ingestedDocument) {
        List<Document> documents = read(resource, ingestedDocument.getContentType());
        List<Document> chunks = etlService.prepare(documents, ingestedDocument);

        if (chunks.isEmpty()) {
            throw new DocumentReadException("No readable text extracted from " + resource.getFilename());
        }

        RetrievalScope scope = scope(ingestedDocument);
        Map<String, Object> scopeMetadata = scopeMetadata(ingestedDocument, scope);

        for (Document chunk : chunks) {
            scope(chunk.getMetadata(), scope, scopeMetadata);
        }

        vectorStoreService.save(chunks);

        ingestedDocumentService.update(ingestedDocument, DocumentStatus.COMPLETED);

        return chunks.size();
    }

    /**
     * A document with no scope recorded predates scoping and is shared, which is what it has always
     * effectively been — the same default the backfill migration applied to chunks already in the
     * store. Nothing writes a null scope any more; the column has been {@code NOT NULL} since
     * {@code V3_25}.
     */
    private static RetrievalScope scope(IngestedDocument ingestedDocument) {
        return ingestedDocument.getScope() == null
                ? RetrievalScope.GLOBAL
                : ingestedDocument.getScope();
    }

    /**
     * The scope-specific keys every chunk of {@code ingestedDocument} is stamped with, read off the
     * row rather than handed in, so no caller can embed a document under metadata that disagrees
     * with what the row says the document is.
     * <p>
     * {@code CHAT} carries the most: its chunks have to be findable by the conversation and, when the
     * document came in on a message, by the one attachment they came from, because deleting either
     * has to delete exactly them.
     */
    private static Map<String, Object> scopeMetadata(IngestedDocument ingestedDocument, RetrievalScope scope) {
        Map<String, Object> scopeMetadata = new HashMap<>();
        scopeMetadata.put(INGESTED_DOCUMENT_ID, ingestedDocument.getId());

        if (scope != RetrievalScope.GLOBAL && ingestedDocument.getUserId() != null) {
            scopeMetadata.put(USER_ID, ingestedDocument.getUserId().toString());
        }

        if (scope == RetrievalScope.CHAT) {
            Map<String, Object> metadata = ingestedDocument.getMetadata() == null
                    ? Map.of()
                    : ingestedDocument.getMetadata();

            Object chatId = metadata.get(IngestedDocument.CHAT_ID);
            Object chatAttachmentId = metadata.get(IngestedDocument.CHAT_ATTACHMENT_ID);

            // Refusing here rather than stamping a null is what keeps a chat chunk retrievable and
            // deletable: the CHAT retrieval tier filters on chatId, and a null is matched by no
            // filter, so a chunk written without one is invisible from the moment it is saved.
            if (chatId == null) {
                throw new IllegalStateException("Chat document " + ingestedDocument.getId()
                        + " is missing the chat id its chunks are retrieved and deleted by");
            }

            // Only a document that arrived on a message has an attachment, and only such a document
            // is ever deleted by attachment id. One uploaded straight to the conversation has
            // neither, and is deleted by its own id or with the whole chat.
            if (ingestedDocument.getDocumentSource() == DocumentSource.CHAT && chatAttachmentId == null) {
                throw new IllegalStateException("Chat document " + ingestedDocument.getId()
                        + " came from an attachment but does not say which");
            }

            scopeMetadata.put(CHAT_ID, chatId);
            scopeMetadata.put(FILE_NAME, ingestedDocument.getFileName());

            if (chatAttachmentId != null) {
                scopeMetadata.put(CHAT_ATTACHMENT_ID, chatAttachmentId);
            }
        }

        return scopeMetadata;
    }

    /**
     * Stamps the scope every retrieval filter reads, plus the scope-specific keys
     * {@link #scopeMetadata(IngestedDocument, RetrievalScope)} derived from the row.
     * <p>
     * The ids in {@code scopeMetadata} go in as strings: a filter expression compares against a JSON
     * string, so a UUID written as anything else would never match.
     */
    private static void scope(Map<String, Object> chunkMetadata, RetrievalScope scope, Map<String, Object> scopeMetadata) {
        chunkMetadata.put(SCOPE, scope.name());
        chunkMetadata.putAll(scopeMetadata);
    }

    /**
     * Puts the bytes back on an attachment-sourced row for the length of a re-ingest.
     * <p>
     * Only the queued path needs this, and only because {@code refresh} can put such a row back in
     * the queue. On the turn it was first sent, {@code ChatDocumentIngestionService} already holds
     * the bytes and hands them in as a {@link Resource} — which is why
     * {@link IngestedDocumentService#beginChatIngestion} leaves {@code fileData} empty and the
     * {@code chat_attachment} row keeps the only copy.
     * <p>
     * Shaped after {@link #fetchUriContent}, and deliberate about not persisting what it loads: the
     * row is written back by {@link IngestedDocumentService#update} at the end of this ingest, so
     * assigning the bytes here would make the second copy this design exists to avoid. They are set
     * on the in-memory instance only, and {@code fileData} is not part of any update the collection
     * performs.
     */
    private void loadChatAttachmentContent(IngestedDocument ingestedDocument) {
        Object chatAttachmentId = ingestedDocument.getMetadata() == null
                ? null
                : ingestedDocument.getMetadata().get(IngestedDocument.CHAT_ATTACHMENT_ID);

        if (chatAttachmentId == null) {
            throw new IllegalStateException("Chat document " + ingestedDocument.getId()
                    + " came from an attachment but does not say which");
        }

        ChatAttachment chatAttachment = chatAttachmentRepository
                .findById(UUID.fromString(chatAttachmentId.toString()))
                .orElseThrow(() -> new IllegalStateException("Chat document " + ingestedDocument.getId()
                        + " has no attachment left to re-read"));

        ingestedDocument.setFileData(chatAttachment.getFileData());
        ingestedDocument.setContentType(chatAttachment.getContentType());
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
