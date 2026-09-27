package com.solesonic.model.xero.invoice;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * The party an invoice is billed to. Only ever the one organisation-wide contact named by
 * {@code xero.invoice.default-contact-id} — this application has no contact lookup and no
 * find-or-create flow.
 * <p>
 * {@code contactId} carries an explicit {@link JsonProperty} because
 * {@link PropertyNamingStrategies.UpperCamelCaseStrategy} renders it {@code ContactId} while Xero
 * spells it {@code ContactID}. That mismatch would not fail: {@code JacksonConfig} disables
 * {@code FAIL_ON_UNKNOWN_PROPERTIES}, so the field would simply arrive null and the outgoing invoice
 * would name no contact at all.
 */
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public record XeroContact(
        @JsonProperty("ContactID")
        String contactId,

        String name) {
}
