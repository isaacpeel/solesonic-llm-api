package com.solesonic.service.push.webpush;

import com.nimbusds.jwt.SignedJWT;
import com.solesonic.config.push.VapidKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class VapidTokenProviderTest {
    private static final URI FCM_ENDPOINT = URI.create("https://fcm.googleapis.com/fcm/send/first");
    private static final URI OTHER_FCM_ENDPOINT = URI.create("https://fcm.googleapis.com/fcm/send/second");
    private static final URI OTHER_ORIGIN_ENDPOINT = URI.create("https://push.example.com/send/third");

    private MutableClock clock;
    private VapidTokenProvider vapidTokenProvider;

    @BeforeEach
    void setUp() {
        KeyPair keyPair = P256Keys.generate();
        clock = new MutableClock(Instant.parse("2026-10-20T12:00:00Z"));
        vapidTokenProvider = new VapidTokenProvider(new VapidKeys((ECPublicKey) keyPair.getPublic(),
                (ECPrivateKey) keyPair.getPrivate(), "mailto:operator@example.com"), clock);
    }

    @Test
    void reusesOneTokenForEveryEndpointOfAnOrigin() {
        assertThat(token(FCM_ENDPOINT)).isEqualTo(token(OTHER_FCM_ENDPOINT));
    }

    @Test
    void signsEachOriginSeparately() throws ParseException {
        assertThat(SignedJWT.parse(token(OTHER_ORIGIN_ENDPOINT)).getJWTClaimsSet().getAudience())
                .containsExactly("https://push.example.com");
        assertThat(token(OTHER_ORIGIN_ENDPOINT)).isNotEqualTo(token(FCM_ENDPOINT));
    }

    @Test
    void keepsATokenUntilAnHourBeforeItExpires() {
        String first = token(FCM_ENDPOINT);

        clock.advance(Duration.ofHours(10));
        assertThat(token(FCM_ENDPOINT)).isEqualTo(first);

        clock.advance(Duration.ofMinutes(61));
        assertThat(token(FCM_ENDPOINT)).isNotEqualTo(first);
    }

    @Test
    void namesAnExplicitPortInTheAudience() throws ParseException {
        String token = token(URI.create("https://push.example.com:8443/send/fourth"));

        assertThat(SignedJWT.parse(token).getJWTClaimsSet().getAudience()).containsExactly("https://push.example.com:8443");
    }

    private String token(URI endpoint) {
        String authorization = vapidTokenProvider.authorization(endpoint);

        return authorization.substring("vapid t=".length(), authorization.indexOf(','));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
