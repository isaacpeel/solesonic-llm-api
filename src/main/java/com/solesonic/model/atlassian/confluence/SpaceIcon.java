package com.solesonic.model.atlassian.confluence;

/**
 * Represents a Confluence space icon.
 */
public class SpaceIcon {
    private String path;
    private String apiDownloadLink;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getApiDownloadLink() {
        return apiDownloadLink;
    }

    public void setApiDownloadLink(String apiDownloadLink) {
        this.apiDownloadLink = apiDownloadLink;
    }
}