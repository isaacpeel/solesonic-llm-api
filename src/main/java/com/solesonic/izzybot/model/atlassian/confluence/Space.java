package com.solesonic.izzybot.model.atlassian.confluence;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.ZonedDateTime;

/**
 * Represents a Confluence space.
 */
public class Space {
    private String id;
    private String key;
    private String name;
    private String type;
    private String status;
    private String authorId;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    private ZonedDateTime createdAt;
    private String homepageId;
    private String spaceOwnerId;
    private SpaceDescription description;
    private SpaceIcon icon;
    @JsonProperty("_links")
    private Links links;
    private String currentActiveAlias;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getAuthorId() {
        return authorId;
    }

    public void setAuthorId(String authorId) {
        this.authorId = authorId;
    }

    public ZonedDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(ZonedDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getHomepageId() {
        return homepageId;
    }

    public void setHomepageId(String homepageId) {
        this.homepageId = homepageId;
    }

    public SpaceDescription getDescription() {
        return description;
    }

    public void setDescription(SpaceDescription description) {
        this.description = description;
    }

    public SpaceIcon getIcon() {
        return icon;
    }

    public void setIcon(SpaceIcon icon) {
        this.icon = icon;
    }

    public Links getLinks() {
        return links;
    }

    public void setLinks(Links links) {
        this.links = links;
    }

    public String getSpaceOwnerId() {
        return spaceOwnerId;
    }

    public void setSpaceOwnerId(String spaceOwnerId) {
        this.spaceOwnerId = spaceOwnerId;
    }

    public String getCurrentActiveAlias() {
        return currentActiveAlias;
    }

    public void setCurrentActiveAlias(String currentActiveAlias) {
        this.currentActiveAlias = currentActiveAlias;
    }
}
