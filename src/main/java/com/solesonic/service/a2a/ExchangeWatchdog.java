package com.solesonic.service.a2a;

import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Fails a delegated turn when the agent goes quiet for too long. It runs only while an exchange is
 * open: a prompt waiting on the user pauses it, so a person deciding is never timed out, and only
 * the next message to the agent starts it again.
 */
final class ExchangeWatchdog {
    private final Duration timeout;
    private final Consumer<Throwable> onTimeout;

    private Disposable pending = Disposables.disposed();
    private boolean active;
    private boolean stopped;
    private long generation;

    ExchangeWatchdog(Duration timeout, Consumer<Throwable> onTimeout) {
        this.timeout = timeout;
        this.onTimeout = onTimeout;
    }

    synchronized void arm() {
        if (stopped) {
            return;
        }

        active = true;
        schedule();
    }

    synchronized void touch() {
        if (active) {
            schedule();
        }
    }

    synchronized void pause() {
        active = false;
        pending.dispose();
    }

    synchronized void stop() {
        stopped = true;
        pause();
    }

    private void schedule() {
        pending.dispose();
        long scheduled = ++generation;
        pending = Mono.delay(timeout).subscribe(_ -> expire(scheduled));
    }

    /**
     * A timer that fired while {@link #touch} was rescheduling it is stale: the generation tells it so.
     */
    private void expire(long scheduled) {
        synchronized (this) {
            if (!active || scheduled != generation) {
                return;
            }

            active = false;
        }

        onTimeout.accept(new TimeoutException("A2A agent went quiet for " + timeout));
    }
}
