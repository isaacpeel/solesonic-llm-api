package com.solesonic.service.atlassian;

import com.solesonic.model.atlassian.confluence.*;
import com.solesonic.model.ingestion.DocumentStatus;
import com.solesonic.model.ingestion.IngestedDocument;
import com.solesonic.model.ingestion.VectorDocument;
import com.solesonic.model.rag.DocumentPrincipal;
import com.solesonic.service.ingestion.IngestedDocumentService;
import com.solesonic.service.rag.VectorStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static com.solesonic.model.ingestion.IngestedDocument.CONFLUENCE_PAGE_VERSION;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ConfluenceServicePageScanTest {

    public static final String CONFLUENCE_PAGE_ID_1 = "c_test_id_1";
    public static final String CONFLUENCE_DELETED_PAGE_ID = "c_deleted_id";
    public static final UUID INGESTED_DOCUMENT_ID_1 = UUID.randomUUID();
    public static final UUID DELETED_INGESTED_DOCUMENT_ID = UUID.randomUUID();

    @Mock
    private IngestedDocumentService ingestedDocumentService;

    @Mock
    private VectorStoreService vectorStoreService;

    @Mock
    private WebClient webClient;

    @SuppressWarnings("rawtypes")
    @Mock
    private WebClient.RequestHeadersUriSpec requestHeadersUriSpec ;

    @SuppressWarnings("rawtypes")
    @Mock
    private WebClient.RequestHeadersSpec requestHeadersSpec;

    private ConfluenceIngestionService confluenceIngestionService;

    @BeforeEach
    public void beforeEach() {
        confluenceIngestionService = new ConfluenceIngestionService(ingestedDocumentService, vectorStoreService, webClient);

        // queue(...) returns the persisted row rather than the one it built, because the id is
        // generated on save and the grants are written against it. An unstubbed mock returns null,
        // which the scan would then dereference.
        lenient().when(ingestedDocumentService.saveWithOwnership(any(IngestedDocument.class),
                        any(DocumentPrincipal.class), any(DocumentPrincipal.class), any(), any()))
                .thenAnswer(invocation -> {
                    IngestedDocument saved = invocation.getArgument(0);
                    saved.setId(UUID.randomUUID());
                    return saved;
                });
    }

    @SuppressWarnings("unchecked")
    @Test
    public void test_with_update() {
        ConfluencePagesResponse confluencePagesResponse = new ConfluencePagesResponse();
        Page page = new Page();
        page.setId(CONFLUENCE_PAGE_ID_1);

        String pageBody = "Penelope";
        Body body = new Body();
        Storage storage = new Storage();
        storage.setValue(pageBody);
        body.setStorage(storage);

        page.setBody(body);

        Version confluenceVersion = new Version();
        confluenceVersion.setNumber(2);
        page.setVersion(confluenceVersion);

        confluencePagesResponse.setResults(List.of(page));

        IngestedDocument ingestedDocument = new IngestedDocument();
        ingestedDocument.setId(INGESTED_DOCUMENT_ID_1);

        Map<String, Object> ingestedDocumentMetadata = new HashMap<>();
        ingestedDocumentMetadata.put(CONFLUENCE_PAGE_VERSION, "1");

        ingestedDocument.setMetadata(ingestedDocumentMetadata);

        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.exchangeToMono(any())).thenReturn(Mono.just(confluencePagesResponse));

        when(ingestedDocumentService.findByConfluencePageId(CONFLUENCE_PAGE_ID_1))
                .thenReturn(List.of(ingestedDocument));

        VectorDocument vectorDocument = new VectorDocument();

        when(vectorStoreService.findByIngestedDocumentId(INGESTED_DOCUMENT_ID_1))
                .thenReturn(List.of(vectorDocument));

        assertThatCode(() -> confluenceIngestionService.pageScan()).doesNotThrowAnyException();

        verify(ingestedDocumentService, times(1)).findByConfluencePageId(CONFLUENCE_PAGE_ID_1);
        verify(vectorStoreService, times(1)).findByIngestedDocumentId(INGESTED_DOCUMENT_ID_1);
        verify(vectorStoreService, times(1)).delete(anyList());
        verify(ingestedDocumentService, times(1)).update(any(), eq(DocumentStatus.REPLACED));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void test_without_update() {
        ConfluencePagesResponse confluencePagesResponse = new ConfluencePagesResponse();
        Page page = new Page();
        page.setId(CONFLUENCE_PAGE_ID_1);

        String pageBody = "Penelope";
        Body body = new Body();
        Storage storage = new Storage();
        storage.setValue(pageBody);
        body.setStorage(storage);

        page.setBody(body);

        Version version = new Version();
        version.setNumber(2);
        page.setVersion(version);

        confluencePagesResponse.setResults(List.of(page));

        IngestedDocument ingestedDocument = new IngestedDocument();
        ingestedDocument.setId(INGESTED_DOCUMENT_ID_1);

        Map<String, Object> ingestedDocumentMetadata = new HashMap<>();
        ingestedDocumentMetadata.put(CONFLUENCE_PAGE_VERSION, "3");

        ingestedDocument.setMetadata(ingestedDocumentMetadata);

        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.exchangeToMono(any())).thenReturn(Mono.just(confluencePagesResponse));

        when(ingestedDocumentService.findByConfluencePageId(CONFLUENCE_PAGE_ID_1))
                .thenReturn(List.of(ingestedDocument));

        assertThatCode(() -> confluenceIngestionService.pageScan()).doesNotThrowAnyException();
        verify(ingestedDocumentService, times(1)).findByConfluencePageId(CONFLUENCE_PAGE_ID_1);

        verify(vectorStoreService, never()).findByIngestedDocumentId(INGESTED_DOCUMENT_ID_1);
        verify(vectorStoreService, never()).delete(anyList());
        verify(ingestedDocumentService, never()).update(any(), eq(DocumentStatus.REPLACED));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void test_with_deletion() {
        ConfluencePagesResponse confluencePagesResponse = new ConfluencePagesResponse();
        Page page = new Page();
        page.setId(CONFLUENCE_PAGE_ID_1);

        String pageBody = "Penelope";
        Body body = new Body();
        Storage storage = new Storage();
        storage.setValue(pageBody);
        body.setStorage(storage);

        page.setBody(body);

        Version version = new Version();
        version.setNumber(2);
        page.setVersion(version);

        confluencePagesResponse.setResults(List.of(page));

        //the live page is already tracked at the same version, so the add/update loop is a no-op for it
        IngestedDocument livePageIngestedDocument = new IngestedDocument();
        livePageIngestedDocument.setId(INGESTED_DOCUMENT_ID_1);

        Map<String, Object> livePageMetadata = new HashMap<>();
        livePageMetadata.put(CONFLUENCE_PAGE_VERSION, "2");
        livePageIngestedDocument.setMetadata(livePageMetadata);

        //a tracked page that no longer exists in confluence
        IngestedDocument deletedPageIngestedDocument = new IngestedDocument();
        deletedPageIngestedDocument.setId(DELETED_INGESTED_DOCUMENT_ID);

        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.exchangeToMono(any())).thenReturn(Mono.just(confluencePagesResponse));

        when(ingestedDocumentService.findByConfluencePageId(CONFLUENCE_PAGE_ID_1))
                .thenReturn(List.of(livePageIngestedDocument));

        when(ingestedDocumentService.findConfluencePageIds())
                .thenReturn(List.of(CONFLUENCE_PAGE_ID_1, CONFLUENCE_DELETED_PAGE_ID));

        when(ingestedDocumentService.findByConfluencePageId(CONFLUENCE_DELETED_PAGE_ID))
                .thenReturn(List.of(deletedPageIngestedDocument));

        assertThatCode(() -> confluenceIngestionService.pageScan()).doesNotThrowAnyException();

        //the deleted page's ingested document is removed, and with it its chunks: clearing those is
        //IngestedDocumentService.delete's own job now, so that no caller can drop a document and
        //leave its chunks behind still answering questions.
        verify(ingestedDocumentService, times(1)).delete(deletedPageIngestedDocument);
        verify(vectorStoreService, never()).findByIngestedDocumentId(DELETED_INGESTED_DOCUMENT_ID);

        //the still-live page is left untouched
        verify(ingestedDocumentService, never()).delete(livePageIngestedDocument);
        verify(vectorStoreService, never()).findByIngestedDocumentId(INGESTED_DOCUMENT_ID_1);
    }
}
