package com.solesonic.service.xero;

import com.solesonic.exception.xero.XeroTokenException;
import com.solesonic.model.xero.auth.XeroAccessToken;
import com.solesonic.model.xero.auth.XeroAuthRequest;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.ZonedDateTime;

import static com.solesonic.config.xero.XeroConstants.XERO_AUTH_WEB_CLIENT;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

/**
 * The one place a Xero token is renewed, shared by the request filter and anything else needing a
 * live token. Modelled on {@link com.solesonic.service.google.GoogleTokenRefreshService}, with two
 * differences that are specific to Xero.
 * <p>
 * The first is what is <em>absent</em>. Google omits the refresh token from most refresh responses,
 * so its service carries the stored one forward; Xero returns a new refresh token every time and
 * invalidates the previous one immediately. A carry-forward branch here would therefore persist a
 * credential that is already dead, and the connection could never renew again.
 * <p>
 * The second is that {@code tenantId}/{@code tenantName} are stamped back on. They are not part of
 * Xero's token response — {@link XeroAuthService} resolves them once from {@code GET /connections}
 * during consent — and every Accounting API call needs the tenant on a header, so dropping them
 * here would break every call made after the first refresh.
 */
@Service
public class XeroTokenRefreshService {
    private static final Logger log = LoggerFactory.getLogger(XeroTokenRefreshService.class);

    /**
     * Xero's answer when a refresh token has been revoked, has expired, or has already been rotated
     * away by a concurrent refresh. Retrying cannot revive it; only re-consent can.
     */
    private static final String INVALID_GRANT = "invalid_grant";

    private static final String NO_REFRESH_TOKEN_ERROR = "no_xero_refresh_token";
    private static final String UNREACHABLE_ERROR = "xero_unreachable";
    private static final String UNREADABLE_ERROR = "xero_response_unreadable";

    @Value("${xero.oauth.client-id}")
    private String clientId;

    @Value("${xero.oauth.client-secret}")
    private String clientSecret;

    private final WebClient authWebClient;
    private final ObjectMapper objectMapper;

    public XeroTokenRefreshService(@Qualifier(XERO_AUTH_WEB_CLIENT) WebClient authWebClient,
                                   ObjectMapper objectMapper) {
        this.authWebClient = authWebClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Exchanges the stored refresh token for a new token pair.
     * <p>
     * {@code created} is stamped here rather than left to the caller because
     * {@link XeroAccessToken#isExpired()} reads a token with no {@code created} as expired, and
     * {@code UserPreferencesService.update} stamps only {@code updated}. An unstamped token would
     * force a refresh on every single call, and each refresh burns the stored refresh token.
     */
    public XeroAccessToken refresh(XeroAccessToken xeroAccessToken) {
        String refreshToken = xeroAccessToken.refreshToken();

        if (StringUtils.isEmpty(refreshToken)) {
            log.warn("No Xero refresh token stored for user: {}", xeroAccessToken.userId());

            throw new XeroTokenException(NO_REFRESH_TOKEN_ERROR, BAD_REQUEST, false);
        }

        XeroAuthRequest xeroAuthRequest = XeroAuthRequest.builder()
                .grantType(XeroAuthService.REFRESH_TOKEN_GRANT)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .refreshToken(refreshToken)
                .build();

        String responseJson = exchangeRefreshToken(xeroAuthRequest);

        XeroAccessToken refreshed = read(responseJson);

        if (StringUtils.isNotEmpty(refreshed.error())) {
            throw tokenException(refreshed.error(), refreshed.errorDescription());
        }

        log.debug("Xero token refresh successful");

        ZonedDateTime now = ZonedDateTime.now();

        return XeroAccessToken.from(refreshed)
                .userId(xeroAccessToken.userId())
                .tenantId(xeroAccessToken.tenantId())
                .tenantName(xeroAccessToken.tenantName())
                .created(now)
                .updated(now)
                .build();
    }

    /**
     * The body is taken whatever the status is, because a failed refresh is precisely where the
     * useful detail lives — Xero names {@code invalid_grant} in the body of a 400, and that is what
     * separates "reconnect" from "retry".
     */
    private String exchangeRefreshToken(XeroAuthRequest xeroAuthRequest) {
        try {
            return authWebClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .pathSegment(XeroAuthService.CONNECT_PATH_SEGMENT, XeroAuthService.TOKEN_PATH_SEGMENT)
                            .build())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(xeroAuthRequest.formData()))
                    .exchangeToMono(response -> response.bodyToMono(String.class))
                    .block();
        } catch (WebClientException webClientException) {
            log.error("Could not reach Xero's token endpoint to refresh", webClientException);

            throw new XeroTokenException(UNREACHABLE_ERROR, SERVICE_UNAVAILABLE, true, webClientException);
        }
    }

    /**
     * Jackson 3 throws an <em>unchecked</em> {@link JacksonException}, so a body that is not JSON at
     * all — an outage page from a CDN in front of Xero, say — would otherwise escape this class and
     * reach {@code GeneralExceptionHandler}, which renders any other {@code RuntimeException} as
     * {@code 200 OK} carrying a chat message.
     */
    private XeroAccessToken read(String json) {
        try {
            return objectMapper.readValue(json, XeroAccessToken.class);
        } catch (JacksonException jacksonException) {
            log.error("Unreadable Xero refresh response", jacksonException);

            throw new XeroTokenException(UNREADABLE_ERROR, SERVICE_UNAVAILABLE, true, jacksonException);
        }
    }

    /**
     * Keeps Xero's own wording out of the response. The error code decides whether the caller is
     * told to reconnect or to retry; the description is logged and dropped.
     */
    private static XeroTokenException tokenException(String error, String errorDescription) {
        log.warn("Xero token refresh failed: {} - {}", error, errorDescription);

        if (INVALID_GRANT.equals(error)) {
            return new XeroTokenException(error, BAD_REQUEST, false);
        }

        return new XeroTokenException(error, SERVICE_UNAVAILABLE, true);
    }
}
