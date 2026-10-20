package com.solesonic.service.push;

import com.solesonic.config.push.PushSettings;
import com.solesonic.exception.push.PushException;
import com.solesonic.model.push.PushErrorCode;
import com.solesonic.model.push.PushTarget;
import com.solesonic.service.push.webpush.P256Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The endpoint is a URL a client chose and the server will POST to, so this is the SSRF boundary.
 * Host matching is exact: a suffix or substring match is what every bypass below relies on.
 */
class PushEndpointValidatorTest {
    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final String CHROME_ENDPOINT = "https://fcm.googleapis.com/fcm/send/dQw4w9WgXcQ:APA91bHPRgkF3JUikC4ENAHEeMrd41Zxv3h"
            + "VZjC9KtT8OvPVGJ-hQMRKRrZuJAEcl7B338qju59zJMjw2DELjzEvxwYv7hH5Ynpc1ODQ0aT4U4OFEeco8ohsN5PjL1iC2dNtk2BAokeMCg2ZXKqpc8FX";

    private final PushEndpointValidator pushEndpointValidator = new PushEndpointValidator(new PushSettings(true,
            Set.of("fcm.googleapis.com"), 3, 5, Duration.ofSeconds(120), Duration.ofSeconds(600),
            Duration.ofSeconds(45), Duration.ofSeconds(15)));

    private String p256dh;
    private String auth;

    @BeforeEach
    void setUp() {
        p256dh = BASE64_URL_ENCODER.encodeToString(P256Keys.uncompressed((ECPublicKey) P256Keys.generate().getPublic()));
        auth = BASE64_URL_ENCODER.encodeToString(authSecret());
    }

    @Test
    void acceptsAChromeSubscription() {
        PushTarget target = pushEndpointValidator.validate(CHROME_ENDPOINT, p256dh, auth);

        assertThat(target.endpoint()).isEqualTo(URI.create(CHROME_ENDPOINT));
        assertThat(target.p256dh()).isEqualTo(p256dh);
        assertThat(target.auth()).isEqualTo(auth);
    }

    @Test
    void matchesTheHostWithoutRegardToCase() {
        assertThat(pushEndpointValidator.validate("https://FCM.googleapis.com/fcm/send/token", p256dh, auth)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://fcm.googleapis.com/fcm/send/token",
            "ftp://fcm.googleapis.com/fcm/send/token",
            "https://user@fcm.googleapis.com/fcm/send/token",
            "https://fcm.googleapis.com@evil.example/fcm/send/token",
            "https://fcm.googleapis.com:443/fcm/send/token",
            "https://fcm.googleapis.com:8443/fcm/send/token",
            "https://fcm.googleapis.com/fcm/send/token#fragment",
            "https://142.250.72.106/fcm/send/token",
            "https://[2607:f8b0:4005:80b::200a]/fcm/send/token",
            "https:fcm.googleapis.com/fcm/send/token",
            "/fcm/send/token",
            "not a url",
            ""})
    void refusesAMalformedEndpoint(String endpoint) {
        assertRejected(endpoint, PushErrorCode.INVALID_SUBSCRIPTION);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://fcm.googleapis.com.evil.example/fcm/send/token",
            "https://evil.example/fcm.googleapis.com",
            "https://evil.fcm.googleapis.com/fcm/send/token",
            "https://fcm.googleapis.com./fcm/send/token",
            "https://updates.push.services.mozilla.com/wpush/v2/gAAAAABk"})
    void refusesAHostOffTheAllowlistAsAnUnsupportedBrowser(String endpoint) {
        assertRejected(endpoint, PushErrorCode.UNSUPPORTED_BROWSER);
    }

    @Test
    void refusesAnEndpointOver2048Characters() {
        String endpoint = "https://fcm.googleapis.com/fcm/send/" + "a".repeat(2048);

        assertRejected(endpoint, PushErrorCode.INVALID_SUBSCRIPTION);
    }

    @Test
    void acceptsAnEndpointOfExactly2048Characters() {
        String prefix = "https://fcm.googleapis.com/fcm/send/";
        String endpoint = prefix + "a".repeat(2048 - prefix.length());

        assertThat(pushEndpointValidator.validate(endpoint, p256dh, auth)).isNotNull();
    }

    @Test
    void refusesAMissingEndpoint() {
        assertRejected(null, PushErrorCode.INVALID_SUBSCRIPTION);
    }

    @Test
    void refusesAP256dhOfTheWrongLength() {
        String shortKey = BASE64_URL_ENCODER.encodeToString(new byte[64]);

        assertThatThrownBy(() -> pushEndpointValidator.validate(CHROME_ENDPOINT, shortKey, auth))
                .isInstanceOfSatisfying(PushException.class,
                        pushException -> assertThat(pushException.getErrorCode()).isEqualTo(PushErrorCode.INVALID_SUBSCRIPTION));
    }

    @Test
    void refusesAP256dhThatIsNotAnUncompressedPoint() {
        byte[] compressed = P256Keys.uncompressed((ECPublicKey) P256Keys.generate().getPublic());
        compressed[0] = 0x02;
        String key = BASE64_URL_ENCODER.encodeToString(compressed);

        assertThatThrownBy(() -> pushEndpointValidator.validate(CHROME_ENDPOINT, key, auth))
                .isInstanceOfSatisfying(PushException.class,
                        pushException -> assertThat(pushException.getErrorCode()).isEqualTo(PushErrorCode.INVALID_SUBSCRIPTION));
    }

    @Test
    void refusesAP256dhThatIsNotOnTheCurve() {
        byte[] offCurve = new byte[65];
        offCurve[0] = 0x04;
        String key = BASE64_URL_ENCODER.encodeToString(offCurve);

        assertThatThrownBy(() -> pushEndpointValidator.validate(CHROME_ENDPOINT, key, auth))
                .isInstanceOfSatisfying(PushException.class,
                        pushException -> assertThat(pushException.getErrorCode()).isEqualTo(PushErrorCode.INVALID_SUBSCRIPTION));
    }

    @Test
    void refusesAnAuthSecretOfTheWrongLength() {
        String shortAuth = BASE64_URL_ENCODER.encodeToString(new byte[15]);

        assertThatThrownBy(() -> pushEndpointValidator.validate(CHROME_ENDPOINT, p256dh, shortAuth))
                .isInstanceOfSatisfying(PushException.class,
                        pushException -> assertThat(pushException.getErrorCode()).isEqualTo(PushErrorCode.INVALID_SUBSCRIPTION));
    }

    @Test
    void refusesKeysThatAreNotBase64Url() {
        assertThatThrownBy(() -> pushEndpointValidator.validate(CHROME_ENDPOINT, "not+base64/url!", auth))
                .isInstanceOfSatisfying(PushException.class,
                        pushException -> assertThat(pushException.getErrorCode()).isEqualTo(PushErrorCode.INVALID_SUBSCRIPTION));
    }

    @Test
    void refusesMissingKeys() {
        assertThatThrownBy(() -> pushEndpointValidator.validate(CHROME_ENDPOINT, null, null))
                .isInstanceOfSatisfying(PushException.class,
                        pushException -> assertThat(pushException.getErrorCode()).isEqualTo(PushErrorCode.INVALID_SUBSCRIPTION));
    }

    private void assertRejected(String endpoint, PushErrorCode expected) {
        assertThatThrownBy(() -> pushEndpointValidator.validate(endpoint, p256dh, auth))
                .isInstanceOfSatisfying(PushException.class,
                        pushException -> assertThat(pushException.getErrorCode()).isEqualTo(expected));
    }

    private static byte[] authSecret() {
        byte[] authSecret = new byte[16];
        new SecureRandom().nextBytes(authSecret);

        return authSecret;
    }
}
