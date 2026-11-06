package com.solesonic.config.push;

import com.solesonic.service.push.webpush.P256Keys;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fails startup on any push misconfiguration. A key that does not decode, or a public key that is not
 * the private key's partner, would otherwise surface only on the first send — after browsers had
 * already subscribed against it.
 */
@Configuration
public class PushConfig {
    public static final String PUSH_REST_CLIENT = "PUSH_REST_CLIENT";

    @Bean
    public VapidKeys vapidKeys(@Value("${solesonic.push.vapid.public-key}") String publicKey,
                               @Value("${solesonic.push.vapid.private-key}") String privateKey,
                               @Value("${solesonic.push.vapid.subject}") String subject) {

        ECPublicKey vapidPublicKey = decodePublicKey(publicKey);
        ECPrivateKey vapidPrivateKey = decodePrivateKey(privateKey);

        if (!P256Keys.isPair(vapidPublicKey, vapidPrivateKey)) {
            throw new IllegalStateException("PUSH_VAPID_PUBLIC_KEY and PUSH_VAPID_PRIVATE_KEY are not a key pair");
        }

        if (subject == null || !(subject.startsWith("mailto:") || subject.startsWith("https://"))) {
            throw new IllegalStateException("PUSH_VAPID_SUBJECT must be a mailto: or https: URI");
        }

        return new VapidKeys(vapidPublicKey, vapidPrivateKey, subject);
    }

    /**
     * The elicitation timeout is read with {@code ElicitationService}'s own default so the two can
     * never disagree about it.
     */
    @Bean
    public PushSettings pushSettings(@Value("${solesonic.push.enabled}") boolean enabled,
                                     @Value("${solesonic.push.allowed-endpoint-hosts}") String allowedEndpointHosts,
                                     @Value("${solesonic.push.max-attempts}") int maxAttempts,
                                     @Value("${solesonic.push.max-failures}") int maxFailures,
                                     @Value("${solesonic.push.elicitation-reminder-seconds}") long elicitationReminderSeconds,
                                     @Value("${solesonic.elicitation.timeout-seconds:600}") long elicitationTimeoutSeconds,
                                     @Value("${solesonic.push.presence.ttl-seconds}") long presenceTtlSeconds,
                                     @Value("${solesonic.push.presence.heartbeat-seconds}") long presenceHeartbeatSeconds) {

        Set<String> hosts = Arrays.stream(allowedEndpointHosts.split(","))
                .map(String::trim)
                .filter(host -> !host.isEmpty())
                .map(host -> host.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());

        if (hosts.isEmpty()) {
            throw new IllegalStateException("PUSH_ALLOWED_ENDPOINT_HOSTS must name at least one host");
        }

        if (maxAttempts < 1) {
            throw new IllegalStateException("PUSH_MAX_ATTEMPTS must be at least 1");
        }

        if (maxFailures < 1) {
            throw new IllegalStateException("PUSH_MAX_FAILURES must be at least 1");
        }

        if (elicitationReminderSeconds <= 0 || elicitationReminderSeconds >= elicitationTimeoutSeconds) {
            throw new IllegalStateException("PUSH_ELICITATION_REMINDER_SECONDS must be greater than 0 and less than "
                    + "solesonic.elicitation.timeout-seconds (" + elicitationTimeoutSeconds + ")");
        }

        if (presenceHeartbeatSeconds <= 0 || presenceHeartbeatSeconds >= presenceTtlSeconds) {
            throw new IllegalStateException("PUSH_PRESENCE_HEARTBEAT_SECONDS must be greater than 0 and less than "
                    + "PUSH_PRESENCE_TTL_SECONDS (" + presenceTtlSeconds + ")");
        }

        return new PushSettings(enabled,
                hosts,
                maxAttempts,
                maxFailures,
                Duration.ofSeconds(elicitationReminderSeconds),
                Duration.ofSeconds(elicitationTimeoutSeconds),
                Duration.ofSeconds(presenceTtlSeconds),
                Duration.ofSeconds(presenceHeartbeatSeconds));
    }

    /**
     * Redirects are never followed: a push service has no reason to send one, and following it would
     * take the request off the allowlisted host.
     */
    @Bean(defaultCandidate = false)
    @Qualifier(PUSH_REST_CLIENT)
    public RestClient pushRestClient(@Value("${solesonic.push.connect-timeout}") Duration connectTimeout,
                                     @Value("${solesonic.push.read-timeout}") Duration readTimeout) {

        HttpClientSettings httpClientSettings = HttpClientSettings.defaults()
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout)
                .withRedirects(HttpRedirects.DONT_FOLLOW);

        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(httpClientSettings))
                .build();
    }

    private static ECPublicKey decodePublicKey(String publicKey) {
        try {
            return P256Keys.publicKey(Base64.getUrlDecoder().decode(publicKey.trim()));
        } catch (IllegalArgumentException illegalArgumentException) {
            throw new IllegalStateException("PUSH_VAPID_PUBLIC_KEY is not a base64url uncompressed P-256 public key",
                    illegalArgumentException);
        }
    }

    /**
     * No cause is attached: nothing about the private key's value may reach a log.
     */
    private static ECPrivateKey decodePrivateKey(String privateKey) {
        try {
            return P256Keys.privateKey(Base64.getUrlDecoder().decode(privateKey.trim()));
        } catch (IllegalArgumentException illegalArgumentException) {
            throw new IllegalStateException("PUSH_VAPID_PRIVATE_KEY is not a base64url 32-byte P-256 private key");
        }
    }
}
