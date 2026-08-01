package com.solesonic.redis.subscriber;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.stream.StreamReceiver;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ChatStreamSubscriber {
    private static final Logger log = LoggerFactory.getLogger(ChatStreamSubscriber.class);
    private static final String DONE_EVENT_TYPE = "done";
    private static final String KEEPALIVE_COMMENT = "keepalive";

    private final ReactiveStringRedisTemplate redisTemplate;
    private final Duration readTimeout;
    private final Duration keepaliveInterval;

    public ChatStreamSubscriber(ReactiveStringRedisTemplate redisTemplate,
                                @Value("${redis.stream.read-timeout-seconds:5}") long readTimeoutSeconds,
                                @Value("${redis.stream.keepalive-seconds:15}") long keepaliveSeconds) {
        this.redisTemplate = redisTemplate;
        this.readTimeout = Duration.ofSeconds(readTimeoutSeconds);
        this.keepaliveInterval = Duration.ofSeconds(keepaliveSeconds);
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

        Flux<ServerSentEvent<?>> events = Flux.from(receiver.receive(offset))
                .map(this::toServerSentEvent);

        return withKeepalive(events);
    }

    /**
     * Puts bytes on the wire while a turn is thinking.
     * <p>
     * Between the model call starting and its first token there is nothing to send, and a
     * {@code text/event-stream} carrying zero bytes is the first thing a mobile radio, a load
     * balancer or a proxy reaps. An SSE comment costs nothing, is dropped by any spec-compliant
     * parser, and is the difference between a backgrounded phone resuming a turn and losing it.
     * <p>
     * Idle-triggered rather than unconditional: a turn already emitting chunks needs no help.
     * Keepalives deliberately carry no {@code id:} — they are not frames, and must not move a
     * client's resume cursor.
     */
    Flux<ServerSentEvent<?>> withKeepalive(Flux<ServerSentEvent<?>> events) {
        AtomicLong lastFrameNanos = new AtomicLong(System.nanoTime());

        Flux<ServerSentEvent<?>> tracked = events
                .doOnNext(_ -> lastFrameNanos.set(System.nanoTime()));

        Flux<ServerSentEvent<?>> keepalives = Flux.interval(keepaliveInterval, keepaliveInterval)
                .filter(_ -> System.nanoTime() - lastFrameNanos.get() >= keepaliveInterval.toNanos())
                .doOnNext(_ -> lastFrameNanos.set(System.nanoTime()))
                .map(_ -> ServerSentEvent.builder().comment(KEEPALIVE_COMMENT).build());

        //takeUntil on the merged flux, so the done frame both reaches the client and cancels the
        //interval. Left on the events flux alone, a finished turn would hold the response open.
        return tracked.mergeWith(keepalives)
                .takeUntil(serverSentEvent -> DONE_EVENT_TYPE.equalsIgnoreCase(serverSentEvent.event()));
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
