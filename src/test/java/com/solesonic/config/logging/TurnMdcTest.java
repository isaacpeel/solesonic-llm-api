package com.solesonic.config.logging;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TurnMdcTest {

    private static final UUID CHAT_ID = UUID.fromString("501e57af-6484-4a5f-bdc8-7d86b5f001fc");

    @BeforeAll
    static void enablePropagation() {
        new ReactorMdcPropagationConfig().enableMdcPropagation();
    }

    @AfterAll
    static void disablePropagation() {
        //A global hook: left on, it changes the timing other test classes in this JVM were written against.
        Hooks.disableAutomaticContextPropagation();
    }

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void captureKeepsTheRequestFieldsAndAddsTheChatId() {
        MDC.put(MdcRequestFilter.REQUEST_ID, "request-1");

        Map<String, String> snapshot = TurnMdc.capture(CHAT_ID);

        assertThat(snapshot).containsEntry(MdcRequestFilter.REQUEST_ID, "request-1")
                .containsEntry(TurnMdc.CHAT_ID, CHAT_ID.toString());
    }

    @Test
    void captureWithAnEmptyMdcStillNamesTheChat() {
        assertThat(TurnMdc.capture(CHAT_ID)).isEqualTo(Map.of(TurnMdc.CHAT_ID, CHAT_ID.toString()));
    }

    @Test
    void aDetachedTurnLogsWithTheChatIdOnAnotherThread() throws InterruptedException {
        MDC.put(MdcRequestFilter.REQUEST_ID, "request-1");
        Map<String, String> snapshot = TurnMdc.capture(CHAT_ID);
        MDC.clear();

        AtomicReference<Map<String, String>> seenOnError = new AtomicReference<>();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);

        Mono.delay(Duration.ofMillis(5))
                .publishOn(Schedulers.boundedElastic())
                .flatMap(_ -> Mono.error(new IllegalStateException("Stream processing failed")))
                .doOnError(_ -> seenOnError.set(MDC.getCopyOfContextMap()))
                .onErrorComplete()
                .doFinally(_ -> done.countDown())
                .contextWrite(TurnMdc.into(snapshot))
                .subscribe();

        assertThat(done.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(seenOnError.get()).containsEntry(TurnMdc.CHAT_ID, CHAT_ID.toString())
                .containsEntry(MdcRequestFilter.REQUEST_ID, "request-1");
    }

    @Test
    void anInnerTurnKeepsItsOwnChatId() {
        UUID outer = UUID.randomUUID();
        AtomicReference<String> seen = new AtomicReference<>();

        Mono.fromRunnable(() -> seen.set(MDC.get(TurnMdc.CHAT_ID)))
                .contextWrite(TurnMdc.into(TurnMdc.capture(CHAT_ID)))
                .contextWrite(TurnMdc.into(TurnMdc.capture(outer)))
                .block();

        assertThat(seen.get()).isEqualTo(CHAT_ID.toString());
    }
}
