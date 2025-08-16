package com.solesonic.service.atlassian;

import com.solesonic.model.atlassian.confluence.ConfluencePagesResponse;
import com.solesonic.model.atlassian.confluence.Page;
import com.solesonic.model.training.DocumentStatus;
import com.solesonic.model.training.TrainingDocument;
import com.solesonic.model.training.VectorDocument;
import com.solesonic.service.rag.TrainingDocumentService;
import com.solesonic.service.rag.VectorStoreService;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.solesonic.config.atlassian.AtlassianConstants.ATLASSIAN_API_INTERNAL_CLIENT;
import static com.solesonic.model.document.DocumentSource.CONFLUENCE;
import static com.solesonic.model.training.TrainingDocument.*;
import static com.solesonic.service.atlassian.ConfluenceConstants.*;
import static org.springframework.http.MediaType.TEXT_HTML_VALUE;

@Service
public class ConfluenceTrainingService {
    private static final Logger log = LoggerFactory.getLogger(ConfluenceTrainingService.class);
    private final TrainingDocumentService trainingDocumentService;
    private final VectorStoreService vectorStoreService;
    private final WebClient webClient;

    private static final String CONFLUENCE_DOCUMENT_FILENAME_TEMPLATE = "[Confluence] %s (v%s)";

    public ConfluenceTrainingService(TrainingDocumentService trainingDocumentService,
                                     VectorStoreService vectorStoreService,
                                     @Qualifier(ATLASSIAN_API_INTERNAL_CLIENT) WebClient webClient) {
        this.trainingDocumentService = trainingDocumentService;
        this.vectorStoreService = vectorStoreService;
        this.webClient = webClient;
    }

    public void pageScan() {
        //get all confluence pages
        ConfluencePagesResponse confluencePagesResponse = adminPages();
        List<Page> pages = confluencePagesResponse.getResults();

        if (CollectionUtils.isNotEmpty(pages)) {
            for (Page confluencePage : pages) {
                String pageId = confluencePage.getId();

                //look for existing training documents, have we added this confluence page to rag before?
                List<TrainingDocument> trainingDocuments = trainingDocumentService.findByConfluencePageId(pageId);

                if (CollectionUtils.isNotEmpty(trainingDocuments)) {
                    //Get the training document with the highest version number;
                    TrainingDocument newestTrainingDocument = trainingDocuments.stream()
                            .max(Comparator.comparing(doc -> (Integer) doc.getMetadata().get(CONFLUENCE_PAGE_VERSION)))
                            .orElse(null);

                    assert newestTrainingDocument != null;
                    Map<String, Object> trainingDocumentMetadata = newestTrainingDocument.getMetadata();
                    Object documentVersion = trainingDocumentMetadata.get(CONFLUENCE_PAGE_VERSION);

                    if (documentVersion != null) {
                        int confluencePageVersion = confluencePage.getVersion().getNumber();
                        int trainingDocumentPageVersion = Integer.parseInt(documentVersion.toString());

                        //there is a new version in confluence, remove the old version and add the new one
                        if (confluencePageVersion > trainingDocumentPageVersion) {
                            List<VectorDocument> vectorDocuments = vectorStoreService.findByTrainingDocumentId(newestTrainingDocument.getId());
                            vectorStoreService.delete(vectorDocuments);

                            //queue the new version of the confluence page to add it to rag
                            TrainingDocument queuedTrainingDocument = queue(confluencePage);
                            trainingDocumentMetadata.put(REPLACED_BY_ID, queuedTrainingDocument.getId());
                            trainingDocumentService.update(newestTrainingDocument, DocumentStatus.REPLACED);
                        }
                    }
                } else {
                    //if the confluence page has never been added to rag then queue it
                    queue(confluencePage);
                }
            }
        }
    }

    public TrainingDocument queue(Page confluencePage) {
        String title = confluencePage.getTitle();
        byte[] fileData = confluencePage.getBody().getStorage().getValue().getBytes();
        String pageId = confluencePage.getId();
        int version = confluencePage.getVersion().getNumber();

        String trainingDocumentFilename = CONFLUENCE_DOCUMENT_FILENAME_TEMPLATE.formatted(title, version);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put(CONFLUENCE_PAGE_ID, pageId);
        metadata.put(CONFLUENCE_PAGE_VERSION, version);

        TrainingDocument trainingDocument = new TrainingDocument();
        trainingDocument.setDocumentStatus(DocumentStatus.QUEUED);
        trainingDocument.setFileName(trainingDocumentFilename);
        trainingDocument.setFileData(fileData);
        trainingDocument.setContentType(TEXT_HTML_VALUE);
        trainingDocument.setMetadata(metadata);
        trainingDocument.setDocumentSource(CONFLUENCE);
        trainingDocument.setCreated(ZonedDateTime.now());
        trainingDocument.setUpdated(ZonedDateTime.now());

        trainingDocumentService.save(trainingDocument);

        return trainingDocument;
    }

    public ConfluencePagesResponse adminPages() {
        log.info("Getting Confluence documents.");
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .pathSegment(basePathSegments)
                        .pathSegment(PAGES_PATH)
                        .queryParam("body-format", STORAGE_FORMAT)
                        .build())
                .exchangeToMono(response -> response.bodyToMono(ConfluencePagesResponse.class))
                .block();

    }
}
