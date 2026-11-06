package com.solesonic.service.push;

import com.solesonic.config.push.PushSettings;
import com.solesonic.exception.push.PushException;
import com.solesonic.model.push.PushErrorCode;
import com.solesonic.model.push.PushTarget;
import com.solesonic.service.push.webpush.P256Keys;
import com.solesonic.service.push.webpush.WebPushEncryption;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The SSRF boundary. An endpoint is a URL a client chose and the server will POST to, so it is
 * checked on every write and again before every send: {@code https}, a host that is exactly one of
 * the allowlisted push services, and nothing that lets a URL say one host and reach another.
 */
@Component
public class PushEndpointValidator {
    public static final int MAX_ENDPOINT_LENGTH = 2048;

    private static final int MAX_ENCODED_KEY_LENGTH = 128;
    private static final String HTTPS = "https";
    private static final Pattern IPV4_LITERAL = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private final PushSettings pushSettings;

    public PushEndpointValidator(PushSettings pushSettings) {
        this.pushSettings = pushSettings;
    }

    public PushTarget validate(String endpoint, String p256dh, String auth) {
        URI endpointUri = endpointUri(endpoint);
        validateKeys(p256dh, auth);

        return new PushTarget(endpointUri, p256dh, auth);
    }

    private URI endpointUri(String endpoint) {
        if (endpoint == null || endpoint.isEmpty() || endpoint.length() > MAX_ENDPOINT_LENGTH) {
            throw invalid();
        }

        URI endpointUri;

        try {
            endpointUri = new URI(endpoint);
        } catch (URISyntaxException uriSyntaxException) {
            throw invalid();
        }

        if (!HTTPS.equals(endpointUri.getScheme()) || endpointUri.isOpaque()) {
            throw invalid();
        }

        if (endpointUri.getRawUserInfo() != null || endpointUri.getPort() != -1 || endpointUri.getRawFragment() != null) {
            throw invalid();
        }

        String host = endpointUri.getHost();

        if (host == null || host.isEmpty() || host.startsWith("[") || IPV4_LITERAL.matcher(host).matches()) {
            throw invalid();
        }

        if (!pushSettings.allowedEndpointHosts().contains(host.toLowerCase(Locale.ROOT))) {
            throw new PushException(PushErrorCode.UNSUPPORTED_BROWSER, "Push notifications are supported in Chrome only.");
        }

        return endpointUri;
    }

    private static void validateKeys(String p256dh, String auth) {
        byte[] userAgentPublicKey = decode(p256dh);
        byte[] authSecret = decode(auth);

        if (userAgentPublicKey == null || authSecret == null || authSecret.length != WebPushEncryption.AUTH_SECRET_LENGTH) {
            throw invalid();
        }

        try {
            P256Keys.publicKey(userAgentPublicKey);
        } catch (IllegalArgumentException illegalArgumentException) {
            throw invalid();
        }
    }

    private static byte[] decode(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_ENCODED_KEY_LENGTH) {
            return null;
        }

        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException illegalArgumentException) {
            return null;
        }
    }

    private static PushException invalid() {
        return new PushException(PushErrorCode.INVALID_SUBSCRIPTION, "The push subscription is not valid.");
    }
}
