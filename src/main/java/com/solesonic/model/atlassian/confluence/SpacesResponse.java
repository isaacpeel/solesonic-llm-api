package com.solesonic.model.atlassian.confluence;

import java.util.List;

/**
 * Represents the response from the Confluence getSpaces endpoint.
 */
public class SpacesResponse {
    private List<Space> results;
    private Links links;

    public List<Space> getResults() {
        return results;
    }

    public void setResults(List<Space> results) {
        this.results = results;
    }

    public Links getLinks() {
        return links;
    }

    public void setLinks(Links links) {
        this.links = links;
    }
}