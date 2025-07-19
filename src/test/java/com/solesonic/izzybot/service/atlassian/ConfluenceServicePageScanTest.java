package com.solesonic.izzybot.service.atlassian;

import com.solesonic.izzybot.model.atlassian.confluence.*;
import com.solesonic.izzybot.model.training.DocumentStatus;
import com.solesonic.izzybot.model.training.TrainingDocument;
import com.solesonic.izzybot.model.training.VectorDocument;
import com.solesonic.izzybot.service.rag.TrainingDocumentService;
import com.solesonic.izzybot.service.rag.VectorStoreService;
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

import static com.solesonic.izzybot.model.training.TrainingDocument.CONFLUENCE_PAGE_VERSION;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ConfluenceServicePageScanTest {

    public static final String CONFLUENCE_PAGE_ID_1 = "c_test_id_1";
    public static final UUID TRAINING_DOCUMENT_ID_1 = UUID.randomUUID();

    @Mock
    private TrainingDocumentService trainingDocumentService;

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

    private ConfluenceTrainingService confluenceTrainingService;

    @BeforeEach
    public void beforeEach() {
        confluenceTrainingService = new ConfluenceTrainingService(trainingDocumentService, vectorStoreService, webClient);
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

        TrainingDocument trainingDocument = new TrainingDocument();
        trainingDocument.setId(TRAINING_DOCUMENT_ID_1);

        Map<String, Object> trainingDocumentMetadata = new HashMap<>();
        trainingDocumentMetadata.put(CONFLUENCE_PAGE_VERSION, "1");

        trainingDocument.setMetadata(trainingDocumentMetadata);

        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.exchangeToMono(any())).thenReturn(Mono.just(confluencePagesResponse));

        when(trainingDocumentService.findByConfluencePageId(CONFLUENCE_PAGE_ID_1))
                .thenReturn(List.of(trainingDocument));

        VectorDocument vectorDocument = new VectorDocument();

        when(vectorStoreService.findByTrainingDocumentId(TRAINING_DOCUMENT_ID_1))
                .thenReturn(List.of(vectorDocument));

        assertThatCode(() -> confluenceTrainingService.pageScan()).doesNotThrowAnyException();

        verify(trainingDocumentService, times(1)).findByConfluencePageId(CONFLUENCE_PAGE_ID_1);
        verify(vectorStoreService, times(1)).findByTrainingDocumentId(TRAINING_DOCUMENT_ID_1);
        verify(vectorStoreService, times(1)).delete(anyList());
        verify(trainingDocumentService, times(1)).update(any(), eq(DocumentStatus.REPLACED));
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

        TrainingDocument trainingDocument = new TrainingDocument();
        trainingDocument.setId(TRAINING_DOCUMENT_ID_1);

        Map<String, Object> trainingDocumentMetadata = new HashMap<>();
        trainingDocumentMetadata.put(CONFLUENCE_PAGE_VERSION, "3");

        trainingDocument.setMetadata(trainingDocumentMetadata);

        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.exchangeToMono(any())).thenReturn(Mono.just(confluencePagesResponse));

        when(trainingDocumentService.findByConfluencePageId(CONFLUENCE_PAGE_ID_1))
                .thenReturn(List.of(trainingDocument));

        assertThatCode(() -> confluenceTrainingService.pageScan()).doesNotThrowAnyException();
        verify(trainingDocumentService, times(1)).findByConfluencePageId(CONFLUENCE_PAGE_ID_1);

        verify(vectorStoreService, never()).findByTrainingDocumentId(TRAINING_DOCUMENT_ID_1);
        verify(vectorStoreService, never()).delete(anyList());
        verify(trainingDocumentService, never()).update(any(), eq(DocumentStatus.REPLACED));
    }
}
