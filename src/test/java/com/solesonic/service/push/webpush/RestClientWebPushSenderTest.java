package com.solesonic.service.push.webpush;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.solesonic.config.push.VapidKeys;
import com.solesonic.model.push.PushDeliveryOptions;
import com.solesonic.model.push.PushDeliveryResult;
import com.solesonic.model.push.PushTarget;
import com.solesonic.model.push.PushUrgency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class RestClientWebPushSenderTest {
    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Pattern VAPID_AUTHORIZATION = Pattern.compile("^vapid t=([^,]+), k=(.+)$");

    private static final URI ENDPOINT = URI.create("https://fcm.googleapis.com/fcm/send/subscription-token");
    private static final String SUBJECT = "mailto:operator@example.com";
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final String TOPIC = "0f8fad5bd9cb469fa16570867728950e";
    private static final byte[] PAYLOAD = "{\"version\":1}".getBytes(StandardCharsets.UTF_8);
    private static final PushDeliveryOptions OPTIONS = new PushDeliveryOptions(Duration.ofHours(24), PushUrgency.HIGH, TOPIC);

    private KeyPair vapidKeyPair;
    private PushTarget target;
    private MockRestServiceServer mockRestServiceServer;
    private RestClientWebPushSender restClientWebPushSender;

    @BeforeEach
    void setUp() {
        vapidKeyPair = P256Keys.generate();

        byte[] authSecret = new byte[16];
        new SecureRandom().nextBytes(authSecret);

        byte[] userAgentPublicKey = P256Keys.uncompressed((ECPublicKey) P256Keys.generate().getPublic());
        target = new PushTarget(ENDPOINT, BASE64_URL_ENCODER.encodeToString(userAgentPublicKey),
                BASE64_URL_ENCODER.encodeToString(authSecret));

        RestClient.Builder restClientBuilder = RestClient.builder();
        mockRestServiceServer = MockRestServiceServer.bindTo(restClientBuilder).build();

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        VapidKeys vapidKeys = new VapidKeys((ECPublicKey) vapidKeyPair.getPublic(),
                (ECPrivateKey) vapidKeyPair.getPrivate(), SUBJECT);

        restClientWebPushSender = new RestClientWebPushSender(restClientBuilder.build(),
                new VapidTokenProvider(vapidKeys, clock), clock);
    }

    @Test
    void postsAnAes128gcmBodyWithTheDeliveryHeaders() {
        mockRestServiceServer.expect(requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.CONTENT_ENCODING, "aes128gcm"))
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(header("TTL", "86400"))
                .andExpect(header("Urgency", "high"))
                .andExpect(header("Topic", TOPIC))
                .andExpect(request -> {
                    byte[] body = ((MockClientHttpRequest) request).getBodyAsBytes();
                    ByteBuffer header = ByteBuffer.wrap(body, 16, 6);

                    assertThat(header.getInt()).isEqualTo(4096);
                    assertThat(header.get()).isEqualTo((byte) 65);
                    assertThat(header.get()).isEqualTo((byte) 0x04);
                    assertThat(body).hasSize(86 + PAYLOAD.length + 1 + 16);
                })
                .andRespond(withStatus(HttpStatus.CREATED));

        PushDeliveryResult result = restClientWebPushSender.send(target, PAYLOAD, OPTIONS);

        assertThat(result).isEqualTo(new PushDeliveryResult(201, null));
        mockRestServiceServer.verify();
    }

    @Test
    void signsAVapidTokenForTheEndpointOrigin() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();

        mockRestServiceServer.expect(requestTo(ENDPOINT))
                .andExpect(request -> authorization.set(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)))
                .andRespond(withStatus(HttpStatus.CREATED));

        restClientWebPushSender.send(target, PAYLOAD, OPTIONS);

        Matcher matcher = VAPID_AUTHORIZATION.matcher(authorization.get());
        assertThat(matcher.matches()).isTrue();
        assertThat(matcher.group(2))
                .isEqualTo(BASE64_URL_ENCODER.encodeToString(P256Keys.uncompressed((ECPublicKey) vapidKeyPair.getPublic())));

        SignedJWT signedJwt = SignedJWT.parse(matcher.group(1));
        assertThat(signedJwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
        assertThat(signedJwt.verify(new ECDSAVerifier((ECPublicKey) vapidKeyPair.getPublic()))).isTrue();

        JWTClaimsSet claims = signedJwt.getJWTClaimsSet();
        assertThat(claims.getAudience()).containsExactly("https://fcm.googleapis.com");
        assertThat(claims.getSubject()).isEqualTo(SUBJECT);
        assertThat(claims.getExpirationTime().toInstant()).isEqualTo(NOW.plus(Duration.ofHours(12)));
        assertThat(Duration.between(NOW, claims.getExpirationTime().toInstant())).isLessThanOrEqualTo(Duration.ofHours(24));
    }

    @Test
    void sendsNoTopicWhenNoneIsGiven() {
        mockRestServiceServer.expect(requestTo(ENDPOINT))
                .andExpect(headerDoesNotExist("Topic"))
                .andRespond(withStatus(HttpStatus.CREATED));

        restClientWebPushSender.send(target, PAYLOAD, new PushDeliveryOptions(Duration.ofMinutes(10), PushUrgency.HIGH, null));

        mockRestServiceServer.verify();
    }

    @Test
    void refusesATopicLongerThan32Characters() {
        PushDeliveryOptions options = new PushDeliveryOptions(Duration.ofHours(1), PushUrgency.HIGH, "a".repeat(33));

        assertThatThrownBy(() -> restClientWebPushSender.send(target, PAYLOAD, options))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reportsAFailureStatusRatherThanThrowing() {
        mockRestServiceServer.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.GONE));

        assertThat(restClientWebPushSender.send(target, PAYLOAD, OPTIONS)).isEqualTo(new PushDeliveryResult(410, null));
    }

    @Test
    void readsRetryAfterInSeconds() {
        mockRestServiceServer.expect(requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "30"));

        assertThat(restClientWebPushSender.send(target, PAYLOAD, OPTIONS))
                .isEqualTo(new PushDeliveryResult(429, Duration.ofSeconds(30)));
    }

    @Test
    void readsRetryAfterAsAnHttpDate() {
        String retryAt = DateTimeFormatter.RFC_1123_DATE_TIME.format(NOW.plusSeconds(90).atZone(ZoneOffset.UTC));

        mockRestServiceServer.expect(requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, retryAt));

        assertThat(restClientWebPushSender.send(target, PAYLOAD, OPTIONS))
                .isEqualTo(new PushDeliveryResult(503, Duration.ofSeconds(90)));
    }

    @Test
    void ignoresAnUnreadableRetryAfter() {
        mockRestServiceServer.expect(requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "soon"));

        assertThat(restClientWebPushSender.send(target, PAYLOAD, OPTIONS)).isEqualTo(new PushDeliveryResult(429, null));
    }
}
