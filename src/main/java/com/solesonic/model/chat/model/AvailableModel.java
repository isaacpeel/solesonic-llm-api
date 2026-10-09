package com.solesonic.model.chat.model;

/**
 * One model the chat endpoint offers, as its {@code models().list()} reports it: nothing parsed or
 * inferred.
 */
public record AvailableModel(String id, String ownedBy) {
}
