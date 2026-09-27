package com.solesonic.model.xero.invoice;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;
import java.util.List;

/**
 * An invoice as it crosses Xero's wire, in both directions: the body this application POSTs, and the
 * body Xero answers with.
 * <p>
 * Three things about this record are load-bearing.
 * <p>
 * <strong>{@code invoiceId} is spelled explicitly.</strong>
 * {@link PropertyNamingStrategies.UpperCamelCaseStrategy} would render it {@code InvoiceId}, but Xero
 * sends {@code InvoiceID}. Because {@code JacksonConfig} disables
 * {@code FAIL_ON_UNKNOWN_PROPERTIES}, getting this wrong is silent — the created invoice's id simply
 * arrives null, and nothing downstream can ever find the document again.
 * <p>
 * <strong>{@code date}/{@code dueDate} are strings, not {@link java.time.LocalDate}.</strong> This
 * application sends ISO ({@code "2026-08-30"}), which Xero accepts, but Xero <em>answers</em> with
 * the Microsoft-style form ({@code "/Date(1756512000000+0000)/"}) that a date type cannot parse.
 * They are therefore carried through opaquely, and {@code dateString}/{@code dueDateString} — the
 * ISO-8601 companions Xero sends beside them — are what a caller should read.
 * <p>
 * <strong>{@code hasErrors} is a {@link Boolean}, not a {@code boolean}.</strong> A primitive would
 * serialize as {@code "HasErrors": false} on every outgoing invoice, putting a read-side field into
 * a write-side body; as a wrapper it is null on the way out and omitted by the mapper's NON_NULL
 * inclusion. Read it with {@code Boolean.TRUE.equals(...)}, since Xero may omit it entirely.
 */
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public record XeroInvoice(
        String type,
        XeroContact contact,
        List<XeroLineItem> lineItems,
        String date,
        String dueDate,
        String reference,
        String currencyCode,
        String lineAmountTypes,
        String status,

        @JsonProperty("InvoiceID")
        String invoiceId,

        String invoiceNumber,
        String dateString,
        String dueDateString,
        BigDecimal subTotal,
        BigDecimal totalTax,
        BigDecimal total,
        Boolean hasErrors,
        List<XeroValidationError> validationErrors) {

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Assembles the <em>outgoing</em> invoice only. There is deliberately no setter for
     * {@code invoiceId}, the totals, {@code hasErrors} or the {@code *String} date renderings: those
     * are Xero's answers, and Jackson fills them through the canonical constructor on the way back.
     */
    public static class Builder {
        private String type;
        private XeroContact contact;
        private List<XeroLineItem> lineItems;
        private String date;
        private String dueDate;
        private String reference;
        private String currencyCode;
        private String lineAmountTypes;
        private String status;

        private Builder() {
        }

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder contact(XeroContact contact) {
            this.contact = contact;
            return this;
        }

        public Builder lineItems(List<XeroLineItem> lineItems) {
            this.lineItems = lineItems;
            return this;
        }

        public Builder date(String date) {
            this.date = date;
            return this;
        }

        public Builder dueDate(String dueDate) {
            this.dueDate = dueDate;
            return this;
        }

        public Builder reference(String reference) {
            this.reference = reference;
            return this;
        }

        public Builder currencyCode(String currencyCode) {
            this.currencyCode = currencyCode;
            return this;
        }

        public Builder lineAmountTypes(String lineAmountTypes) {
            this.lineAmountTypes = lineAmountTypes;
            return this;
        }

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        /**
         * {@code invoiceNumber} is left null on purpose, not merely unset: Xero generates one from
         * the organisation's own numbering settings, and supplying our own risks colliding with a
         * number that organisation has already issued.
         */
        public XeroInvoice build() {
            return new XeroInvoice(
                    type,
                    contact,
                    lineItems,
                    date,
                    dueDate,
                    reference,
                    currencyCode,
                    lineAmountTypes,
                    status,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
    }
}
