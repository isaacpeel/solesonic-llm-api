package com.solesonic.exception.handler;

import com.solesonic.exception.xero.XeroApiException;
import com.solesonic.exception.xero.XeroErrorResponse;
import com.solesonic.exception.xero.XeroInvoiceValidationException;
import com.solesonic.exception.xero.XeroTokenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.reactive.function.client.ClientResponse;

import java.net.URI;

import static com.solesonic.exception.xero.XeroErrorResponse.INTERNAL;
import static com.solesonic.exception.xero.XeroErrorResponse.RATE_LIMITED;
import static com.solesonic.exception.xero.XeroErrorResponse.RECONNECT_REQUIRED;
import static com.solesonic.exception.xero.XeroErrorResponse.UPSTREAM_UNAVAILABLE;
import static com.solesonic.exception.xero.XeroErrorResponse.VALIDATION_FAILED;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;

/**
 * Turns Xero failures into something a caller can act on: a real status code and a stable error
 * code. Xero's own error text is logged, never returned.
 * <p>
 * Deliberately <em>not</em> built on {@link ExceptionService}, the way
 * {@code AtlassianExceptionHandler} is. That renders a failure as {@code 200 OK} carrying a chat
 * message, which suits Atlassian because its failures surface mid-conversation. The Xero endpoints
 * are plain REST — the OAuth callback answers {@code 204} on success, so a {@code 200} that means
 * "it failed" would be read by the connecting browser as "it worked".
 * <p>
 * Without this class {@link GeneralExceptionHandler}'s catch-all {@code RuntimeException} branch
 * would do exactly that, so it is not optional symmetry with the Google handler.
 */
@ControllerAdvice
public class XeroExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(XeroExceptionHandler.class);

    private static final String RECONNECT_MESSAGE = "Xero access is no longer valid. Reconnect your Xero organisation.";
    private static final String RATE_LIMITED_MESSAGE = "Xero is rate limiting requests. Please try again shortly.";
    private static final String UPSTREAM_MESSAGE = "Xero is temporarily unavailable. Please try again.";
    private static final String INTERNAL_MESSAGE = "Internal service error. Please contact Isaac.";

    /**
     * The {@link ClientResponse} is optional. It is present when the API {@code WebClient}'s response
     * filter raised this for a non-2xx, and absent when the service raised it for a {@code 200} whose
     * bulk envelope carried no invoice at all. Dereferencing it unconditionally would throw from
     * inside a {@code @ControllerAdvice}, replacing a reportable failure with an unreportable one.
     */
    @ExceptionHandler(XeroApiException.class)
    public ResponseEntity<XeroErrorResponse> handleXeroApiException(XeroApiException xeroApiException) {
        ClientResponse clientResponse = xeroApiException.getResponse();

        if (clientResponse == null) {
            log.error("Xero API error with no failing response: {}", xeroApiException.getMessage());
        } else {
            URI requestUri = clientResponse.request().getURI();

            log.error("Xero API error calling {}", requestUri);
        }

        return ResponseEntity.status(INTERNAL_SERVER_ERROR)
                .body(new XeroErrorResponse(UPSTREAM_UNAVAILABLE, UPSTREAM_MESSAGE));
    }

    /**
     * The one Xero failure whose upstream text is returned verbatim.
     * <p>
     * Xero answers a rejected invoice with {@code 200} once {@code summarizeErrors=false} is set, so
     * without this branch the exception would fall to {@link GeneralExceptionHandler}'s catch-all and
     * be rendered as {@code 200 OK} carrying a chat message — which a caller of a creation endpoint
     * reads as "the invoice was created".
     * <p>
     * Unlike an OAuth error body, these messages are safe to return: they are accounting validation
     * wording written for the person who submitted the document, and they are the only thing that
     * tells a caller which line item to fix.
     */
    @ExceptionHandler(XeroInvoiceValidationException.class)
    public ResponseEntity<XeroErrorResponse> handleXeroInvoiceValidationException(
            XeroInvoiceValidationException xeroInvoiceValidationException) {
        String messages = String.join(" ", xeroInvoiceValidationException.getMessages());

        log.warn("Xero rejected an invoice: {}", messages);

        return ResponseEntity.status(BAD_REQUEST)
                .body(new XeroErrorResponse(VALIDATION_FAILED, messages));
    }

    @ExceptionHandler(XeroTokenException.class)
    public ResponseEntity<XeroErrorResponse> handleXeroTokenException(XeroTokenException xeroTokenException) {
        HttpStatusCode statusCode = xeroTokenException.getErrorCode();
        boolean retriable = xeroTokenException.isRetriable();
        String message = xeroTokenException.getMessage();

        if (BAD_REQUEST.equals(statusCode)) {
            log.warn("Invalid Xero token - {}", message);

            return ResponseEntity.status(BAD_REQUEST)
                    .body(new XeroErrorResponse(RECONNECT_REQUIRED, RECONNECT_MESSAGE));
        }

        if (TOO_MANY_REQUESTS.equals(statusCode)) {
            log.warn("Rate limited by Xero - {}", message);

            return ResponseEntity.status(TOO_MANY_REQUESTS)
                    .body(new XeroErrorResponse(RATE_LIMITED, RATE_LIMITED_MESSAGE));
        }

        if (retriable) {
            log.warn("Retriable Xero API error - {}", message);

            return ResponseEntity.status(SERVICE_UNAVAILABLE)
                    .body(new XeroErrorResponse(UPSTREAM_UNAVAILABLE, UPSTREAM_MESSAGE));
        }

        log.error("Non-retriable Xero API error - {}", message);

        return ResponseEntity.status(INTERNAL_SERVER_ERROR)
                .body(new XeroErrorResponse(INTERNAL, INTERNAL_MESSAGE));
    }
}
