package com.solesonic.izzybot.service.atlassian;

import com.solesonic.izzybot.model.atlassian.confluence.Space;
import com.solesonic.izzybot.model.atlassian.confluence.SpacesResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import static com.solesonic.izzybot.config.atlassian.AtlassianConstants.ATLASSIAN_API_WEB_CLIENT;
import static com.solesonic.izzybot.service.atlassian.ConfluenceConstants.SPACES_PATH;
import static com.solesonic.izzybot.service.atlassian.ConfluenceConstants.basePathSegments;

@Service
public class ConfluenceSpaceService {
    private static final Logger log = LoggerFactory.getLogger(ConfluenceSpaceService.class);

    private final WebClient webClient;

    public ConfluenceSpaceService(@Qualifier(ATLASSIAN_API_WEB_CLIENT) WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * Retrieves all spaces from Confluence.
     *
     * @return A response containing a list of spaces
     */
    public SpacesResponse getSpaces() {
        log.info("Getting all Confluence spaces");
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .pathSegment(basePathSegments)
                        .pathSegment(SPACES_PATH)
                        .build())
                .exchangeToMono(response -> response.bodyToMono(SpacesResponse.class))
                .block();
    }

    /**
     * Retrieves a specific space from Confluence by ID.
     *
     * @param id The ID of the space to retrieve
     * @return The space with the specified ID
     */
    public Space getSpace(String id) {
        log.info("Getting Confluence space: {}", id);
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .pathSegment(basePathSegments)
                        .pathSegment(SPACES_PATH)
                        .pathSegment(id)
                        .build())
                .exchangeToMono(response -> response.bodyToMono(Space.class))
                .block();
    }

    /**
     * Creates a new space in Confluence.
     * @param space the space to create
     * @return The created space
     */
    public Space createSpace(Space space) {
        log.info("Creating Confluence space: {}", space.getName());

        return webClient.post()
                .uri(uriBuilder -> uriBuilder
                        .pathSegment(basePathSegments)
                        .pathSegment(SPACES_PATH)
                        .build())
                .bodyValue(space)
                .exchangeToMono(response -> response.bodyToMono(Space.class))
                .block();
    }
}