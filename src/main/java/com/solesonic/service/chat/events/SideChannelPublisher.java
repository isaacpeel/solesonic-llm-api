package com.solesonic.service.chat.events;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * The one definition of a chat's side channel: its pub/sub key and its {@code {event, data}}
 * envelope. A payload is serialized once on the way in and parsed once on the way out.
 */
@Component
public class SideChannelPublisher {
    private static final String EVENTS_CHANNEL_PREFIX = "elicitation:events:";
    private static final String CLOSE_EVENT = "__close__";

    private final JsonMapper jsonMapper;
    private final ReactiveStringRedisTemplate redisTemplate;

    public SideChannelPublisher(JsonMapper jsonMapper, ReactiveStringRedisTemplate redisTemplate) {
        this.jsonMapper = jsonMapper;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Serializes now, sends on subscription. A payload that cannot be written fails the caller here,
     * before anything it would have published after is done.
     *
     * @return the number of subscribers that received the message
     */
    public Mono<Long> publish(UUID chatId, String event, Object data) {
        String envelope = jsonMapper.writeValueAsString(new SideChannelMessage(event, jsonMapper.valueToTree(data)));

        return Mono.defer(() -> redisTemplate.convertAndSend(eventsChannelKey(chatId), envelope));
    }

    public Mono<Long> close(UUID chatId) {
        return publish(chatId, CLOSE_EVENT, "");
    }

    /**
     * Shared, so every operator downstream reads one pub/sub subscription; it completes when the
     * channel is closed.
     */
    public Flux<SideChannelMessage> listen(UUID chatId) {
        return redisTemplate.listenToChannel(eventsChannelKey(chatId))
                .map(message -> jsonMapper.readValue(message.getMessage(), SideChannelMessage.class))
                .takeWhile(message -> !CLOSE_EVENT.equals(message.event()))
                .share();
    }

    private static String eventsChannelKey(UUID chatId) {
        return EVENTS_CHANNEL_PREFIX + chatId;
    }
}
