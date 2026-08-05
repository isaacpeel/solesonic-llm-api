package com.solesonic.service.atlassian;

import com.solesonic.model.atlassian.confluence.ConfluencePagesResponse;
import com.solesonic.model.atlassian.confluence.Page;
import com.solesonic.model.atlassian.confluence.ResponseLinks;
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
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private static final String CURSOR_PARAM = "cursor";
    private static final int PAGE_FETCH_LIMIT = 250;

    public ConfluenceTrainingService(TrainingDocumentService trainingDocumentService,
                                     VectorStoreService vectorStoreService,
                                     @Qualifier(ATLASSIAN_API_INTERNAL_CLIENT) WebClient webClient) {
        this.trainingDocumentService = trainingDocumentService;
        this.vectorStoreService = vectorStoreService;
        this.webClient = webClient;
    }

    public void pageScan() {
        //get all confluence pages across every pagination cursor
        List<Page> pages = allPages();

        Set<String> livePageIds = new HashSet<>();

        if (CollectionUtils.isNotEmpty(pages)) {
            for (Page confluencePage : pages) {
                String pageId = confluencePage.getId();
                livePageIds.add(pageId);

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

        //remove documents whose confluence pages no longer exist
        removeDeletedPages(livePageIds);
    }

    private void removeDeletedPages(Set<String> livePageIds) {
        if (livePageIds.isEmpty()) {
            //an empty live set almost always signals a fetch problem, not that confluence is empty.
            //bail out rather than delete every tracked document.
            log.warn("No live confluence pages retrieved; skipping deletion pass to avoid removing all tracked documents.");
            return;
        }

        List<String> trackedPageIds = trainingDocumentService.findConfluencePageIds();

        for (String trackedPageId : trackedPageIds) {
            if (livePageIds.contains(trackedPageId)) {
                continue;
            }

            log.info("Confluence page {} no longer exists; removing its tracked documents.", trackedPageId);

            List<TrainingDocument> trainingDocuments = trainingDocumentService.findByConfluencePageId(trackedPageId);

            if (CollectionUtils.isEmpty(trainingDocuments)) {
                continue;
            }

            for (TrainingDocument trainingDocument : trainingDocuments) {
                List<VectorDocument> vectorDocuments = vectorStoreService.findByTrainingDocumentId(trainingDocument.getId());
                vectorStoreService.delete(vectorDocuments);
                trainingDocumentService.delete(trainingDocument);
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

    public List<Page> allPages() {
        log.info("Getting Confluence documents.");

        List<Page> pages = new ArrayList<>();
        String cursor = null;

        do {
            ConfluencePagesResponse confluencePagesResponse = pageBatch(cursor);

            if (confluencePagesResponse == null) {
                break;
            }

            List<Page> results = confluencePagesResponse.getResults();

            if (CollectionUtils.isNotEmpty(results)) {
                pages.addAll(results);
            }

            cursor = nextCursor(confluencePagesResponse);
        } while (cursor != null);

        return pages;
    }

    private ConfluencePagesResponse pageBatch(String cursor) {
        return webClient.get()
                .uri(uriBuilder -> {
                    uriBuilder
                            .pathSegment(basePathSegments)
                            .pathSegment(PAGES_PATH)
                            .queryParam("body-format", STORAGE_FORMAT)
                            .queryParam("limit", PAGE_FETCH_LIMIT);

                    if (cursor != null) {
                        uriBuilder.queryParam(CURSOR_PARAM, cursor);
                    }

                    return uriBuilder.build();
                })
                .exchangeToMono(response -> response.bodyToMono(ConfluencePagesResponse.class))
                .block();
    }

    private String nextCursor(ConfluencePagesResponse confluencePagesResponse) {
        ResponseLinks links = confluencePagesResponse.getLinks();

        if (links == null || links.getNext() == null) {
            return null;
        }

        MultiValueMap<String, String> queryParams = UriComponentsBuilder.fromUriString(links.getNext())
                .build()
                .getQueryParams();

        return queryParams.getFirst(CURSOR_PARAM);
    }
}
