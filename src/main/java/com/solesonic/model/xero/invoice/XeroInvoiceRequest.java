package com.solesonic.model.xero.invoice;

import java.time.LocalDate;
import java.util.List;

/**
 * What a caller may say about an invoice — and, by omission, what it may not.
 * <p>
 * There is no {@code type}, no {@code contact} and no {@code status} component here, and their
 * absence is the feature rather than an oversight. Every invoice this application creates is a
 * draft ({@code DRAFT}) sales invoice ({@code ACCREC}) billed to the one contact named by
 * {@code xero.invoice.default-contact-id}; {@link com.solesonic.service.xero.XeroInvoiceService}
 * sets all three itself on every call. A field for any of them would make a policy into an input,
 * and in particular would give a caller a way to post a live, sendable invoice into someone's
 * accounting system without a human ever seeing it.
 * <p>
 * {@code date} and {@code dueDate} are real {@link LocalDate}s here because this side of the
 * exchange is ISO-8601. Xero answers with Microsoft-style dates, which is why {@link XeroInvoice}
 * carries them as strings instead.
 */
public record XeroInvoiceRequest(
        List<XeroLineItemRequest> lineItems,
        LocalDate date,
        LocalDate dueDate,
        String reference,
        String currencyCode,
        String lineAmountTypes) {
}
