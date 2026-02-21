package com.solesonic.redis.subscriber;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.stream.StreamReceiver;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;

@Service
@ConditionalOnProperty(name = "redis.stream.enabled", havingValue = "true")
public class ChatStreamSubscriber {
    private static final Logger log = LoggerFactory.getLogger(ChatStreamSubscriber.class);
    private static final String DONE_EVENT_TYPE = "done";

    private final ReactiveStringRedisTemplate redisTemplate;
    private final Duration readTimeout;

    public ChatStreamSubscriber(ReactiveStringRedisTemplate redisTemplate,
                                @Value("${redis.stream.read-timeout-seconds:5}") long readTimeoutSeconds) {
        this.redisTemplate = redisTemplate;
        this.readTimeout = Duration.ofSeconds(readTimeoutSeconds);
    }

    public Flux<ServerSentEvent<?>> subscribe(String streamKey, String lastEventId) {
        StreamOffset<String> offset = resolveOffset(streamKey, lastEventId);

        log.debug("Subscribing to stream {} from offset {}", streamKey, offset);

        StreamReceiver.StreamReceiverOptions<String, MapRecord<String, String, String>> receiverOptions =
                StreamReceiver.StreamReceiverOptions.builder()
                        .pollTimeout(readTimeout)
                        .build();

        StreamReceiver<String, MapRecord<String, String, String>> receiver =
                StreamReceiver.create(redisTemplate.getConnectionFactory(), receiverOptions);

        return Flux.from(receiver.receive(offset)
                .map(this::toServerSentEvent)
                .takeUntil(sse -> DONE_EVENT_TYPE.equalsIgnoreCase(sse.event())));
    }

    private StreamOffset<String> resolveOffset(String streamKey, String lastEventId) {
        if (lastEventId != null && !lastEventId.isBlank()) {
            log.debug("Resuming stream {} from last event id {}", streamKey, lastEventId);

            return StreamOffset.create(streamKey, ReadOffset.from(lastEventId));
        }

        return StreamOffset.create(streamKey, ReadOffset.from("0"));
    }

    private ServerSentEvent<?> toServerSentEvent(MapRecord<String, String, String> record) {
        String redisRecordId = record.getId().getValue();
        String eventType = record.getValue().getOrDefault("type", "chunk");
        String payload = record.getValue().getOrDefault("payload", "");

        return ServerSentEvent.builder()
                .id(redisRecordId)
                .event(eventType)
                .data(payload)
                .build();
    }
}
