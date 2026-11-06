package com.solesonic.service.chat.events;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SideChannelPublisherTest {
    private static final UUID CHAT_ID = UUID.randomUUID();

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private SideChannelPublisher sideChannelPublisher;
    private Sinks.Many<String> messageRelay;

    @BeforeEach
    void setUp() {
        messageRelay = Sinks.many().replay().all();

        Flux<ReactiveSubscription.Message<String, String>> listenerFlux = messageRelay.asFlux()
                .map(body -> new FixedChannelMessage("elicitation:events:" + CHAT_ID, body));

        lenient().doReturn(listenerFlux).when(redisTemplate).listenToChannel("elicitation:events:" + CHAT_ID);

        lenient().when(redisTemplate.convertAndSend(anyString(), anyString())).thenAnswer(invocation -> {
            messageRelay.tryEmitNext(invocation.getArgument(1));
            return Mono.just(1L);
        });

        sideChannelPublisher = new SideChannelPublisher(jsonMapper, redisTemplate);
    }

    @Test
    void publishesOneEnvelopeOnTheChatsChannel() {
        StepVerifier.create(sideChannelPublisher.publish(CHAT_ID, "progress", Map.of("message", "Searching")))
                .expectNext(1L)
                .verifyComplete();

        ArgumentCaptor<String> envelope = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).convertAndSend(eq("elicitation:events:" + CHAT_ID), envelope.capture());

        assertThat(jsonMapper.readTree(envelope.getValue()))
                .isEqualTo(jsonMapper.readTree("{\"event\":\"progress\",\"data\":{\"message\":\"Searching\"}}"));
    }

    @Test
    void listenedMessagesKeepTheirPayloadAsATree() {
        sideChannelPublisher.publish(CHAT_ID, "progress", Map.of("message", "Searching")).block();

        StepVerifier.create(sideChannelPublisher.listen(CHAT_ID).take(1))
                .assertNext(message -> {
                    assertThat(message.event()).isEqualTo("progress");
                    assertThat(message.data().path("message").asString()).isEqualTo("Searching");
                })
                .verifyComplete();
    }

    @Test
    void aStringPayloadArrivesAsATextNode() {
        sideChannelPublisher.publish(CHAT_ID, "cancel", "cancel").block();

        StepVerifier.create(sideChannelPublisher.listen(CHAT_ID).take(1))
                .assertNext(message -> {
                    assertThat(message.data().isString()).isTrue();
                    assertThat(message.data().asString()).isEqualTo("cancel");
                })
                .verifyComplete();
    }

    @Test
    void closingTheChannelEndsTheListener() {
        sideChannelPublisher.publish(CHAT_ID, "progress", Map.of("message", "Searching")).block();
        sideChannelPublisher.close(CHAT_ID).block();
        sideChannelPublisher.publish(CHAT_ID, "progress", Map.of("message", "Too late")).block();

        StepVerifier.create(sideChannelPublisher.listen(CHAT_ID))
                .assertNext(message -> assertThat(message.data().path("message").asString()).isEqualTo("Searching"))
                .expectComplete()
                .verify(Duration.ofSeconds(1));
    }

    /**
     * Serialization happens when the call is made, not when the send is subscribed to, so a payload
     * that cannot be written fails its caller before anything else is done on its behalf.
     */
    @Test
    void anUnserializablePayloadFailsTheCallAndSendsNothing() {
        Callable<Mono<Long>> publication = () -> sideChannelPublisher.publish(CHAT_ID, "progress", new Unserializable());

        assertThatThrownBy(publication::call).isInstanceOf(JacksonException.class);

        verify(redisTemplate, never()).convertAndSend(anyString(), anyString());
    }

    @Test
    void theSendWaitsForSubscription() {
        Mono<Long> publication = sideChannelPublisher.publish(CHAT_ID, "progress", Map.of("message", "Searching"));

        verify(redisTemplate, never()).convertAndSend(anyString(), anyString());

        StepVerifier.create(publication).expectNext(1L).verifyComplete();
    }

    public static final class Unserializable {
        public String getValue() {
            throw new IllegalStateException("unwritable");
        }
    }

    private record FixedChannelMessage(String channelName, String messageBody)
            implements ReactiveSubscription.Message<String, String> {

        @Override
        public @NonNull String getChannel() {
            return channelName;
        }

        @Override
        public @NonNull String getMessage() {
            return messageBody;
        }
    }
}
