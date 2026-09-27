package com.solesonic.exception.xero;

import org.springframework.http.HttpStatusCode;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;

/**
 * The only Xero failure wording a person is ever allowed to see.
 * <p>
 * This exists because a Xero failure can now reach a person two ways, and only one of them passes
 * through a {@code @ControllerAdvice}. {@code XeroExceptionHandler} renders a REST failure and
 * discards the upstream text; a failure inside {@code CreateXeroInvoiceTools} never reaches that
 * handler at all — Spring AI turns an exception thrown by a tool into the tool's <em>result</em>,
 * which is narrated to the user and persisted in chat history. Both paths therefore have to censor
 * the same things, and one copy of the wording is what stops them from disagreeing about what is
 * safe to say.
 * <p>
 * What is being kept back is Xero's own error body. It is written for whoever integrated with Xero,
 * not for the person holding the invoice, and it can name tokens, grants and organisation internals.
 * The one Xero failure whose text <em>is</em> safe to repeat is
 * {@link XeroInvoiceValidationException}, which carries accounting validation wording aimed at the
 * person who submitted the document — that one is deliberately absent from this class.
 */
public final class XeroErrorMessages {

    public static final String RECONNECT_MESSAGE =
            "Xero access is no longer valid. Reconnect your Xero organisation.";

    public static final String RATE_LIMITED_MESSAGE =
            "Xero is rate limiting requests. Please try again shortly.";

    public static final String UPSTREAM_MESSAGE =
            "Xero is temporarily unavailable. Please try again.";

    public static final String INTERNAL_MESSAGE = "Internal service error. Please contact Isaac.";

    private XeroErrorMessages() {
    }

    /**
     * The safe wording for a token failure, chosen the same way {@code XeroExceptionHandler} chooses
     * the status it answers with, so a chat user and a REST caller are told the same thing about the
     * same failure.
     */
    public static String forTokenException(XeroTokenException xeroTokenException) {
        HttpStatusCode errorCode = xeroTokenException.getErrorCode();

        if (BAD_REQUEST.equals(errorCode)) {
            return RECONNECT_MESSAGE;
        }

        if (TOO_MANY_REQUESTS.equals(errorCode)) {
            return RATE_LIMITED_MESSAGE;
        }

        if (xeroTokenException.isRetriable()) {
            return UPSTREAM_MESSAGE;
        }

        return INTERNAL_MESSAGE;
    }
}
