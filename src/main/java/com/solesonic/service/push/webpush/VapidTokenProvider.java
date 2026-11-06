package com.solesonic.service.push.webpush;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.solesonic.config.push.VapidKeys;
import com.solesonic.exception.push.WebPushException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RFC 8292 sender identity: an ES256 JWT whose audience is the push service's origin. One token per
 * origin is reused until an hour before it expires, so a fan-out to many subscriptions signs once.
 */
@Component
public class VapidTokenProvider {
    private static final Duration TOKEN_LIFETIME = Duration.ofHours(12);
    private static final Duration RENEW_BEFORE_EXPIRY = Duration.ofHours(1);

    private final VapidKeys vapidKeys;
    private final Clock clock;
    private final String publicKey;
    private final Map<String, VapidToken> tokensByAudience = new ConcurrentHashMap<>();

    public VapidTokenProvider(VapidKeys vapidKeys, Clock clock) {
        this.vapidKeys = vapidKeys;
        this.clock = clock;
        this.publicKey = vapidKeys.publicKeyBase64Url();
    }

    public String authorization(URI endpoint) {
        Instant now = clock.instant();

        VapidToken vapidToken = tokensByAudience.compute(origin(endpoint), (audience, existing) ->
                existing != null && now.isBefore(existing.expiresAt().minus(RENEW_BEFORE_EXPIRY))
                        ? existing
                        : sign(audience, now));

        return "vapid t=" + vapidToken.value() + ", k=" + publicKey;
    }

    private VapidToken sign(String audience, Instant now) {
        Instant expiresAt = now.plus(TOKEN_LIFETIME);

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .audience(audience)
                .expirationTime(Date.from(expiresAt))
                .subject(vapidKeys.subject())
                .build();

        SignedJWT signedJwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType.JWT).build(), claims);

        try {
            signedJwt.sign(new ECDSASigner(vapidKeys.privateKey()));
        } catch (JOSEException joseException) {
            throw new WebPushException("Could not sign the VAPID token", joseException);
        }

        return new VapidToken(signedJwt.serialize(), expiresAt);
    }

    private static String origin(URI endpoint) {
        String origin = endpoint.getScheme() + "://" + endpoint.getHost();

        if (endpoint.getPort() == -1) {
            return origin;
        }

        return origin + ":" + endpoint.getPort();
    }

    private record VapidToken(String value, Instant expiresAt) {
    }
}
