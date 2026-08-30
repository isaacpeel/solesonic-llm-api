package com.solesonic.model.xero.invoice;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;

/**
 * A line of an invoice as it crosses Xero's wire, in both directions.
 * <p>
 * Money and quantities are {@link BigDecimal} throughout. {@code taxAmount} and {@code lineItemId}
 * are Xero's to compute and assign — they are sent as nulls and dropped by the mapper's NON_NULL
 * inclusion, so an outgoing line carries only what the caller supplied.
 * <p>
 * {@code lineItemId} needs its explicit {@link JsonProperty} for the same reason
 * {@link XeroContact#contactId()} does: Xero spells it {@code LineItemID}, and the naming strategy
 * would produce {@code LineItemId}.
 */
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public record XeroLineItem(
        String description,
        BigDecimal quantity,
        BigDecimal unitAmount,
        BigDecimal lineAmount,
        String accountCode,
        String itemCode,
        String taxType,
        BigDecimal taxAmount,

        @JsonProperty("LineItemID")
        String lineItemId) {

    /** The write side: exactly the fields a caller may supply, with Xero's own left for Xero. */
    public static XeroLineItem from(XeroLineItemRequest xeroLineItemRequest) {
        return new XeroLineItem(
                xeroLineItemRequest.description(),
                xeroLineItemRequest.quantity(),
                xeroLineItemRequest.unitAmount(),
                xeroLineItemRequest.lineAmount(),
                xeroLineItemRequest.accountCode(),
                xeroLineItemRequest.itemCode(),
                xeroLineItemRequest.taxType(),
                null,
                null);
    }
}
