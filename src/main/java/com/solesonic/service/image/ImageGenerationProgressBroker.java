package com.solesonic.service.image;

import com.solesonic.model.image.ImageGenerationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes the frames of one in-flight generation to the client watching it.
 * <p>
 * A generation is identified by a UUID that is also the MCP {@code _meta.progressToken}, which is
 * what lets {@code ProgressProvider} — a single callback shared with chat progress — decide where a
 * {@code notifications/progress} belongs without the two paths knowing about each other.
 * <p>
 * Nothing here is persisted, and nothing here is chat memory: image progress is transient by
 * nature, and an image generation has no conversation to be part of.
 */
@Component
public class ImageGenerationProgressBroker {
    private static final Logger log = LoggerFactory.getLogger(ImageGenerationProgressBroker.class);

    /**
     * How long an emitter will spin through a non-serialized or overflowing emission before giving
     * up on a frame. Progress notifications arrive on the MCP transport thread while the terminal
     * frame is emitted from the thread running the generation, so contention is possible, brief,
     * and never worth failing a generation over.
     */
    private static final Duration EMIT_BUSY_LOOP = Duration.ofMillis(100);

    private final Map<UUID, Sinks.Many<ImageGenerationEvent>> generations = new ConcurrentHashMap<>();

    /**
     * Opens a generation, so that its progress token is recognised as one.
     * <p>
     * Required even for a generation nobody is watching: an unopened token is indistinguishable
     * from a chat id to the shared progress callback, and its frames would land in chat history.
     */
    public void open(UUID generationId) {
        log.debug("Opening image generation {}", generationId);

        generations.put(generationId, Sinks.many().unicast().onBackpressureBuffer());
    }

    /**
     * The frames of an open generation, or an empty stream if it has already ended.
     * <p>
     * The sink buffers, so frames emitted between {@link #open} and the client's subscription are
     * delivered rather than dropped.
     */
    public Flux<ImageGenerationEvent> frames(UUID generationId) {
        Sinks.Many<ImageGenerationEvent> sink = generations.get(generationId);

        if (sink == null) {
            log.warn("No open image generation {} to stream", generationId);

            return Flux.empty();
        }

        return sink.asFlux();
    }

    /**
     * @return false when {@code generationId} names no open generation — which is how the shared
     * progress callback tells an image progress token from a chat id
     */
    public boolean emit(UUID generationId, ImageGenerationEvent imageGenerationEvent) {
        Sinks.Many<ImageGenerationEvent> sink = generations.get(generationId);

        if (sink == null) {
            return false;
        }

        try {
            sink.emitNext(imageGenerationEvent, Sinks.EmitFailureHandler.busyLooping(EMIT_BUSY_LOOP));
        } catch (Sinks.EmissionException emissionException) {
            //The ordinary cause is a client that has already disconnected. The generation itself is
            //unaffected — the image is still persisted — so this is a lost frame, not a failure.
            log.debug("Dropped an image generation frame for {}: {}", generationId, emissionException.getMessage());
        }

        return true;
    }

    /**
     * Ends the stream and forgets the generation. Safe to call more than once, and required on
     * every exit path — an entry left behind here is a leak that also shadows a chat id.
     */
    public void close(UUID generationId) {
        Sinks.Many<ImageGenerationEvent> sink = generations.remove(generationId);

        if (sink == null) {
            return;
        }

        log.debug("Closing image generation {}", generationId);

        try {
            sink.emitComplete(Sinks.EmitFailureHandler.busyLooping(EMIT_BUSY_LOOP));
        } catch (Sinks.EmissionException emissionException) {
            log.debug("Could not close the image generation stream for {}: {}",
                    generationId, emissionException.getMessage());
        }
    }
}
