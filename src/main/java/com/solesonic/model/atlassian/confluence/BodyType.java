package com.solesonic.model.atlassian.confluence;

/**
 * Represents a body type in Confluence.
 */
public class BodyType {
    private String representation;
    private String value;

    public String getRepresentation() {
        return representation;
    }

    public void setRepresentation(String representation) {
        this.representation = representation;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}