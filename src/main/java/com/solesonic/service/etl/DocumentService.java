package com.solesonic.service.etl;

import com.solesonic.exception.rag.DocumentReadException;
import com.solesonic.model.chat.attachment.ChatAttachment;
import com.solesonic.model.document.DocumentSource;
import com.solesonic.model.ingestion.DocumentStatus;
import com.solesonic.model.ingestion.IngestedDocument;
import com.solesonic.repository.chat.ChatAttachmentRepository;
import com.solesonic.service.ingestion.DocumentEntitlementService;
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
import static com.solesonic.model.rag.RetrievalMetadata.ENTITLEMENTS;
import static com.solesonic.model.rag.RetrievalMetadata.FILE_NAME;
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
    private final DocumentEntitlementService documentEntitlementService;

    public DocumentService(VectorStoreService vectorStoreService,
                           EtlService etlService,
                           IngestedDocumentService ingestedDocumentService,
                           UriContentFetcher uriContentFetcher,
                           ChatAttachmentRepository chatAttachmentRepository,
                           DocumentEntitlementService documentEntitlementService) {
        this.vectorStoreService = vectorStoreService;
        this.etlService = etlService;
        this.ingestedDocumentService = ingestedDocumentService;
        this.uriContentFetcher = uriContentFetcher;
        this.chatAttachmentRepository = chatAttachmentRepository;
        this.documentEntitlementService = documentEntitlementService;
    }

    /**
     * Stores the given resource to the vector store
     */
    public void resourceToVectorStore(UUID ingestedDocumentId) {
        log.info("Saving resource to the vector store.");

        IngestedDocument ingestedDocument = ingestedDocumentService.get(ingestedDocumentId);

        byte[] fileContent = content(ingestedDocument);

        String contentType = ingestedDocument.getContentType();

        ByteArrayResource resource = new ByteArrayResource(fileContent) {
            @Override
            public String getFilename() {
                return ingestedDocument.getFileName();
            }
        };

        assert contentType != null;

        List<Document> documents = read(resource, contentType);

        documents = etlService.prepare(documents, ingestedDocument);

        Map<String, Object> chunkMetadata = chunkMetadata(ingestedDocument);

        for (Document document : documents) {
            document.getMetadata().putAll(chunkMetadata);
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

        Map<String, Object> chunkMetadata = chunkMetadata(ingestedDocument);

        for (Document chunk : chunks) {
            chunk.getMetadata().putAll(chunkMetadata);
        }

        vectorStoreService.save(chunks);

        ingestedDocumentService.update(ingestedDocument, DocumentStatus.COMPLETED);

        return chunks.size();
    }

    /**
     * Everything every chunk of {@code ingestedDocument} is stamped with: who may retrieve it, and
     * where it came from.
     * <p>
     * The entitlement half is <em>not</em> assembled here. It is read from
     * {@link DocumentEntitlementService#retrievalKeys(UUID)}, which is the only producer of those
     * keys anywhere in the codebase — this method's predecessor hand-built them from the row's
     * columns and metadata map while the columns themselves were hand-set at five separate call
     * sites, so the two could and did disagree. {@code V3_25} exists because of that. Now the chunk
     * array is a projection of the {@code document_entitlement} rows with one place to change.
     * <p>
     * The ids go in as strings: a filter expression compares against a JSON string, so a UUID
     * written as anything else would never match.
     */
    private Map<String, Object> chunkMetadata(IngestedDocument ingestedDocument) {
        Map<String, Object> chunkMetadata = new HashMap<>();
        chunkMetadata.put(INGESTED_DOCUMENT_ID, ingestedDocument.getId());
        chunkMetadata.put(FILE_NAME, ingestedDocument.getFileName());

        List<String> entitlements = documentEntitlementService.retrievalKeys(ingestedDocument.getId());

        // Refusing rather than defaulting. A chunk written with no entitlement is matched by no
        // filter and so is retrievable by nobody -- the document would report COMPLETED and answer
        // nothing, silently. Defaulting to "global" instead would turn the same bug into a
        // disclosure, which is strictly worse, so neither is acceptable and this fails loudly.
        if (entitlements.isEmpty()) {
            throw new IllegalStateException("Document " + ingestedDocument.getId()
                    + " has no retrieve grant; its chunks would be retrievable by nobody");
        }

        chunkMetadata.put(ENTITLEMENTS, entitlements);

        Map<String, Object> metadata = ingestedDocument.getMetadata() == null
                ? Map.of()
                : ingestedDocument.getMetadata();

        Object chatId = metadata.get(IngestedDocument.CHAT_ID);
        Object chatAttachmentId = metadata.get(IngestedDocument.CHAT_ATTACHMENT_ID);

        // Only a document that arrived on a message has an attachment, and only such a document is
        // ever deleted by attachment id. One uploaded straight to the conversation has none, and is
        // deleted by its own id or with the whole chat.
        if (ingestedDocument.getDocumentSource() == DocumentSource.CHAT && chatAttachmentId == null) {
            throw new IllegalStateException("Chat document " + ingestedDocument.getId()
                    + " came from an attachment but does not say which");
        }

        // Provenance, stamped whenever the row carries it rather than only at one scope: it is what
        // teardown matches on, and a document promoted out of its conversation keeps it precisely so
        // that deleting that conversation still reaches the chunks that came from it.
        if (chatId != null) {
            chunkMetadata.put(CHAT_ID, chatId);
        }

        if (chatAttachmentId != null) {
            chunkMetadata.put(CHAT_ATTACHMENT_ID, chatAttachmentId);
        }

        return chunkMetadata;
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
     * The bytes to ingest, from wherever this document's kind keeps them.
     * <p>
     * Three cases, and the difference between them is ownership of the only copy. A {@code URI}
     * document has none until it is fetched; a {@code CHAT} document's copy belongs to the
     * {@code chat_attachment} row it arrived on; everything else stored its own at upload.
     */
    private byte[] content(IngestedDocument ingestedDocument) {
        if (ingestedDocument.getDocumentSource() == DocumentSource.URI) {
            return fetchUriContent(ingestedDocument);
        }

        if (ingestedDocument.getDocumentSource() == DocumentSource.CHAT) {
            return loadChatAttachmentContent(ingestedDocument);
        }

        return ingestedDocumentService.content(ingestedDocument.getId())
                .orElseThrow(() -> new DocumentReadException(
                        "Document " + ingestedDocument.getId() + " has no stored content to ingest"));
    }

    /**
     * Reads an attachment-sourced document's bytes back off the attachment for the length of a
     * re-ingest.
     * <p>
     * Only the queued path needs this, and only because {@code refresh} can put such a row back in
     * the queue. On the turn it was first sent, {@code ChatDocumentIngestionService} already holds
     * the bytes and hands them in as a {@link Resource}.
     * <p>
     * Deliberately not persisted. The attachment keeps the only copy, and writing a second one here
     * would double the storage and be free to drift from the one the attachment actually serves.
     * Promotion is the one operation that does copy them, because it is the one that severs the
     * document from the attachment.
     */
    private byte[] loadChatAttachmentContent(IngestedDocument ingestedDocument) {
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

        ingestedDocument.setContentType(chatAttachment.getContentType());

        return chatAttachment.getFileData();
    }

    /**
     * Fetches a URI document's content and stores it, which is the point at which such a row stops
     * being "bytes live elsewhere" and gains a content row of its own.
     */
    private byte[] fetchUriContent(IngestedDocument ingestedDocument) {
        Object sourceUri = ingestedDocument.getMetadata().get(IngestedDocument.SOURCE_URI);
        assert sourceUri != null;

        UriContentFetcher.FetchedContent fetchedContent = uriContentFetcher.fetch(sourceUri.toString());

        ingestedDocument.setContentType(fetchedContent.contentType());
        ingestedDocument.getMetadata().put(IngestedDocument.FILE_SIZE_BYTES, fetchedContent.data().length);

        ingestedDocumentService.storeContent(ingestedDocument.getId(), fetchedContent.data());

        return fetchedContent.data();
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
