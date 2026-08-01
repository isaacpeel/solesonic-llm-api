package com.solesonic.redis.subscriber;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ChatStreamSubscriberTest {

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    private ChatStreamSubscriber subscriber() {
        return new ChatStreamSubscriber(redisTemplate, 5L, 1L);
    }

    private static ServerSentEvent<?> frame(String event) {
        return ServerSentEvent.builder().id("1").event(event).data("{}").build();
    }

    @Test
    void emitsKeepalivesWhileTheTurnIsSilent() {
        List<ServerSentEvent<?>> emitted = subscriber()
                .withKeepalive(Flux.never())
                .take(2)
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(emitted).hasSize(2);
        assertThat(emitted).allSatisfy(serverSentEvent -> {
            assertThat(serverSentEvent.comment()).isEqualTo("keepalive");
            assertThat(serverSentEvent.data()).isNull();
            //A keepalive must never move a client's resume cursor.
            assertThat(serverSentEvent.id()).isNull();
        });
    }

    @Test
    void staysQuietWhileFramesAreFlowing() {
        Flux<ServerSentEvent<?>> busy = Flux.interval(Duration.ofMillis(100))
                .take(12)
                .map(_ -> frame("chunk"));

        List<ServerSentEvent<?>> emitted = subscriber()
                .withKeepalive(busy)
                .take(Duration.ofMillis(1400))
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(emitted).isNotEmpty();
        assertThat(emitted).noneSatisfy(serverSentEvent ->
                assertThat(serverSentEvent.comment()).isNotNull());
    }

    @Test
    void completesOnDoneAndDropsAnythingAfterIt() {
        List<ServerSentEvent<?>> emitted = subscriber()
                .withKeepalive(Flux.just(frame("chunk"), frame("done"), frame("chunk")))
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(emitted).hasSize(2);
        assertThat(emitted).last().satisfies(serverSentEvent ->
                assertThat(serverSentEvent.event()).isEqualTo("done"));
    }
}
