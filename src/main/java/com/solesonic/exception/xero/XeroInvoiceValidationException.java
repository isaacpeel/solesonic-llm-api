package com.solesonic.exception.xero;

import java.util.List;

/**
 * Xero accepted the request and refused the invoice.
 * <p>
 * This is the exception that exists because a {@code 200} from Xero is not proof of success. With
 * {@code summarizeErrors=false} on the query string — which is what makes a rejection explainable at
 * all, rather than a bare {@code 400} — Xero answers {@code 200 OK} and reports the failure inside
 * the invoice, on {@code HasErrors} and {@code ValidationErrors[].Message}. A service that trusted
 * the status line would tell a caller their draft exists when nothing was created.
 * <p>
 * Unlike every other Xero failure in this package, the messages here are safe to return verbatim:
 * they are accounting validation wording written for the person who submitted the document, not the
 * developer-facing OAuth detail that {@link XeroTokenException} deliberately keeps to the log.
 */
public class XeroInvoiceValidationException extends RuntimeException {
    private final List<String> messages;

    public XeroInvoiceValidationException(List<String> messages) {
        super(String.join("; ", messages));
        this.messages = List.copyOf(messages);
    }

    public List<String> getMessages() {
        return messages;
    }
}
