package com.solesonic.service.chat.events;

import com.solesonic.service.chat.ChatMessageService;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.Mockito.*;

import org.springframework.data.redis.core.ReactiveValueOperations;

@ExtendWith(MockitoExtension.class)
class ElicitationServiceTest {
    @Mock
    private ChatMessageService chatMessageService;

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    @Mock
    private ReactiveValueOperations<String, String> valueOperations;

    private Sinks.Many<String> messageRelay;

    @BeforeEach
    void setUp() {
        JsonMapper jsonMapper = JsonMapper.builder().build();
        messageRelay = Sinks.many().multicast().onBackpressureBuffer();

        lenient().doReturn(valueOperations).when(redisTemplate).opsForValue();

        Flux<ReactiveSubscription.Message<String, String>> listenerFlux = messageRelay.asFlux()
                .map(body -> new FixedChannelMessage("channel", body));

        lenient().doReturn(listenerFlux).when(redisTemplate).listenToChannel(any(String.class));

        lenient().when(redisTemplate.convertAndSend(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    messageRelay.tryEmitNext(invocation.getArgument(1));
                    return Mono.just(1L);
                });

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
