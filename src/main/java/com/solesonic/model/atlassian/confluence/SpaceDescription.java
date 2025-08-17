package com.solesonic.model.atlassian.confluence;

/**
 * Represents a Confluence space description.
 * Contains fields for each representation type requested.
 */
public class SpaceDescription {
    private BodyType plain;
    private BodyType view;

    public BodyType getPlain() {
        return plain;
    }

    public void setPlain(BodyType plain) {
        this.plain = plain;
    }

    public BodyType getView() {
        return view;
    }

    public void setView(BodyType view) {
        this.view = view;
    }
}