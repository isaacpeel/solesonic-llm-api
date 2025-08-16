package com.solesonic.tools.confluence;

import com.solesonic.model.atlassian.confluence.Body;
import com.solesonic.model.atlassian.confluence.Page;
import com.solesonic.model.atlassian.confluence.Storage;
import com.solesonic.service.atlassian.ConfluencePageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import static com.solesonic.service.atlassian.ConfluenceConstants.STORAGE_FORMAT;

@Component
public class CreateConfluenceTools {
    private static final Logger log = LoggerFactory.getLogger(CreateConfluenceTools.class);
    
    public static final String CREATE_CONFLUENCE_PAGE = "create_confluence_page";
    public static final String DEFAULT_SPACE_ID = "16154645";
    public static final String CONFLUENCE_URL_TEMPLATE = "https://solesonic-llm-api.atlassian.net/wiki/spaces/{spaceId}/pages/{pageId}";

    private final ConfluencePageService confluencePageService;

    public CreateConfluenceTools(ConfluencePageService confluencePageService) {
        this.confluencePageService = confluencePageService;
    }

    public record CreateConfluencePageResponse(String pageId, String pageUri) {}
    public record CreateConfluencePageRequest(String title, String content) {}

    @SuppressWarnings("unused")
    @Tool(name = CREATE_CONFLUENCE_PAGE, description = "Creates a confluence page. Use responsibly and ensure no repeated calls for the same request.")
    public CreateConfluencePageResponse createConfluencePage(CreateConfluencePageRequest request) {
        log.debug("Invoking create confluence page function");
        log.debug("Title: {}", request.title);
        log.debug("Content length: {}", request.content != null ? request.content.length() : 0);

        // Create the page object
        Page page = new Page();
        page.setTitle(request.title);
        page.setSpaceId(DEFAULT_SPACE_ID);
        page.setStatus("current");

        // Set up the body with content
        Body body = new Body();
        Storage storage = new Storage();
        storage.setRepresentation(STORAGE_FORMAT);
        storage.setValue(request.content != null ? request.content : "");
        body.setStorage(storage);
        page.setBody(body);

        // Create the page in Confluence
        Page createdPage = confluencePageService.createPage(page);
        
        log.debug("Created confluence page: {}", createdPage.getId());

        // Generate the page URI
        String pageUri = CONFLUENCE_URL_TEMPLATE
                .replace("{spaceId}", DEFAULT_SPACE_ID)
                .replace("{pageId}", createdPage.getId());

        log.debug("Using page uri: {}", pageUri);

        return new CreateConfluencePageResponse(createdPage.getId(), pageUri);
    }
}