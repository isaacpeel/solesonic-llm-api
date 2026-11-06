package com.solesonic.service.push.webpush;

import com.solesonic.config.push.PushConfig;
import com.solesonic.model.push.PushDeliveryOptions;
import com.solesonic.model.push.PushDeliveryResult;
import com.solesonic.model.push.PushTarget;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.regex.Pattern;

@Service
public class RestClientWebPushSender implements WebPushSender {
    private static final String TTL = "TTL";
    private static final String URGENCY = "Urgency";
    private static final String TOPIC = "Topic";
    private static final String AES128GCM = "aes128gcm";
    private static final Pattern TOPIC_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Pattern DELTA_SECONDS = Pattern.compile("\\d{1,9}");

    private final RestClient restClient;
    private final VapidTokenProvider vapidTokenProvider;
    private final Clock clock;

    public RestClientWebPushSender(@Qualifier(PushConfig.PUSH_REST_CLIENT) RestClient restClient,
                                   VapidTokenProvider vapidTokenProvider,
                                   Clock clock) {
        this.restClient = restClient;
        this.vapidTokenProvider = vapidTokenProvider;
        this.clock = clock;
    }

    @Override
    public PushDeliveryResult send(PushTarget target, byte[] payload, PushDeliveryOptions options) {
        String topic = options.topic();

        if (topic != null && !TOPIC_PATTERN.matcher(topic).matches()) {
            throw new IllegalArgumentException("A push Topic is 1 to 32 base64url characters");
        }

        Base64.Decoder decoder = Base64.getUrlDecoder();
        byte[] body = WebPushEncryption.encrypt(payload, decoder.decode(target.p256dh()), decoder.decode(target.auth()));
        String authorization = vapidTokenProvider.authorization(target.endpoint());

        return restClient.post()
                .uri(target.endpoint())
                .headers(headers -> {
                    headers.set(TTL, String.valueOf(options.timeToLive().toSeconds()));
                    headers.set(URGENCY, options.urgency().headerValue());
                    headers.set(HttpHeaders.CONTENT_ENCODING, AES128GCM);
                    headers.set(HttpHeaders.AUTHORIZATION, authorization);

                    if (topic != null) {
                        headers.set(TOPIC, topic);
                    }
                })
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(body)
                .exchangeForRequiredValue((_, response) ->
                        new PushDeliveryResult(response.getStatusCode().value(), retryAfter(response.getHeaders())));
    }

    /**
     * RFC 9110 allows either delta-seconds or an HTTP-date. Anything else is treated as absent rather
     * than failing a delivery whose status has already been read.
     */
    private Duration retryAfter(HttpHeaders headers) {
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);

        if (value == null || value.isBlank()) {
            return null;
        }

        String trimmed = value.trim();

        if (DELTA_SECONDS.matcher(trimmed).matches()) {
            return Duration.ofSeconds(Long.parseLong(trimmed));
        }

        try {
            Duration untilRetry = Duration.between(clock.instant(),
                    ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());

            return untilRetry.isNegative() ? Duration.ZERO : untilRetry;
        } catch (DateTimeParseException dateTimeParseException) {
            return null;
        }
    }
}
