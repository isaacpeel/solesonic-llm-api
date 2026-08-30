package com.solesonic.exception.handler;

import com.solesonic.exception.xero.XeroApiException;
import com.solesonic.exception.xero.XeroErrorResponse;
import com.solesonic.exception.xero.XeroTokenException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.ClientResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;

/**
 * A Xero failure must arrive as a failing status code.
 * <p>
 * This matters more here than the mirror-the-Google-handler symmetry suggests.
 * {@link GeneralExceptionHandler} catches every {@code RuntimeException} and renders it through
 * {@link ExceptionService} as {@code 200 OK} carrying a chat message — right for a failure that
 * surfaces mid-conversation, and wrong for the OAuth callback, which answers {@code 204} on
 * success. Without this handler a failed Xero connect would reach the browser as a success.
 */
class XeroExceptionHandlerTest {

    private final XeroExceptionHandler xeroExceptionHandler = new XeroExceptionHandler();

    @Test
    void answersBadRequestWithReconnectRequiredWhenTheGrantIsGone() {
        XeroTokenException xeroTokenException =
                new XeroTokenException("invalid_grant", BAD_REQUEST, false);

        ResponseEntity<XeroErrorResponse> response =
                xeroExceptionHandler.handleXeroTokenException(xeroTokenException);

        assertThat(response.getStatusCode()).isEqualTo(BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(XeroErrorResponse.RECONNECT_REQUIRED);
    }

    @Test
    void answersServiceUnavailableWhenTheFailureIsRetriable() {
        XeroTokenException xeroTokenException =
                new XeroTokenException("backend_error", SERVICE_UNAVAILABLE, true);

        ResponseEntity<XeroErrorResponse> response =
                xeroExceptionHandler.handleXeroTokenException(xeroTokenException);

        assertThat(response.getStatusCode()).isEqualTo(SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(XeroErrorResponse.UPSTREAM_UNAVAILABLE);
    }

    /**
     * Xero rate limits at 60 requests/minute per app, 5,000/day per organisation and 5 concurrent
     * per organisation — low enough that a caller genuinely needs to tell throttling apart from a
     * broken connection.
     */
    @Test
    void answersTooManyRequestsWhenXeroThrottles() {
        XeroTokenException xeroTokenException =
                new XeroTokenException("rate_limit", TOO_MANY_REQUESTS, true);

        ResponseEntity<XeroErrorResponse> response =
                xeroExceptionHandler.handleXeroTokenException(xeroTokenException);

        assertThat(response.getStatusCode()).isEqualTo(TOO_MANY_REQUESTS);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(XeroErrorResponse.RATE_LIMITED);
    }

    @Test
    void answersInternalServerErrorWhenTheFailureIsNotRetriable() {
        XeroTokenException xeroTokenException =
                new XeroTokenException("boom", INTERNAL_SERVER_ERROR, false);

        ResponseEntity<XeroErrorResponse> response =
                xeroExceptionHandler.handleXeroTokenException(xeroTokenException);

        assertThat(response.getStatusCode()).isEqualTo(INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(XeroErrorResponse.INTERNAL);
    }

    /**
     * A non-2xx from the Xero API arrives as a {@link XeroApiException} carrying the raw upstream
     * body. None of it may reach the caller — only the failing status and the stable code.
     */
    @Test
    void answersInternalServerErrorForAFailedXeroApiCallWithoutEchoingItsBody() {
        ClientResponse clientResponse = ClientResponse.create(INTERNAL_SERVER_ERROR).build();
        XeroApiException xeroApiException =
                new XeroApiException("{\"Detail\":\"TenantId 5C0B4C1F not recognised\"}", clientResponse);

        ResponseEntity<XeroErrorResponse> response =
                xeroExceptionHandler.handleXeroApiException(xeroApiException);

        assertThat(response.getStatusCode()).isEqualTo(INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(XeroErrorResponse.UPSTREAM_UNAVAILABLE);
        assertThat(response.getBody().message()).doesNotContain("5C0B4C1F");
        assertThat(response.getBody().message()).doesNotContain("Detail");
    }

    /**
     * Xero's own OAuth error text names client and tenant identifiers; only the fixed, user-safe
     * wording may leave.
     */
    @Test
    void neverReturnsXerosOwnErrorText() {
        XeroTokenException xeroTokenException = new XeroTokenException(
                "invalid_grant: refresh token revoked for client 5C0B4C1F", BAD_REQUEST, false);

        ResponseEntity<XeroErrorResponse> response =
                xeroExceptionHandler.handleXeroTokenException(xeroTokenException);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).doesNotContain("invalid_grant");
        assertThat(response.getBody().message()).doesNotContain("5C0B4C1F");
    }
}
