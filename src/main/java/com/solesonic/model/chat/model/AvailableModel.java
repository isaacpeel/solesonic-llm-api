package com.solesonic.model.chat.model;

/**
 * One model the chat endpoint offers, as its {@code models().list()} reports it, with the display
 * settings an admin may set in the model's LiteLLM {@code model_info}.
 *
 * @param id          the name a request carries; always the endpoint's own spelling
 * @param displayName what a picker shows: {@code model_info.display_name}, or the id when none is set
 * @param hidden      {@code model_info.ui_hidden}: a picker leaves the model out, but it stays selectable
 *                    by name and a chat already on it keeps showing its label
 */
public record AvailableModel(String id, String ownedBy, String displayName, boolean hidden) {

    /**
     * A model with no display settings: shown, under its id.
     */
    public AvailableModel(String id, String ownedBy) {
        this(id, ownedBy, id, false);
    }
}
