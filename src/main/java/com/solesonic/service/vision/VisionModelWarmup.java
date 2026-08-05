package com.solesonic.service.vision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static com.solesonic.config.olllama.VisionOllamaConfig.VISION_CHAT_MODEL;

/**
 * Preloads the vision model on startup, for the same reason {@code EtlModelWarmup} does it for the
 * ETL models — but with a user watching, which changes the stakes.
 * <p>
 * The first image-bearing turn after a restart otherwise pays a cold model load, and a load slow
 * enough to outlive the read timeout produces an assistant that answers as though no image was
 * attached. Paying that cost here, before anyone is waiting on it, is what makes the failure rare;
 * the {@code attachment} event that {@link ImageDescriptionService} emits is what makes it visible
 * when it happens anyway.
 * <p>
 * A text-only prompt is enough: Ollama loads the whole model, projector included, before it runs
 * any inference, so no image is needed to make the load happen.
 */
@Component
public class VisionModelWarmup {
    private static final Logger log = LoggerFactory.getLogger(VisionModelWarmup.class);
    private static final String WARMUP_PROMPT = "ok";

    private final OllamaChatModel visionChatModel;
    private final boolean warmupOnStartup;

    public VisionModelWarmup(@Qualifier(VISION_CHAT_MODEL) OllamaChatModel visionChatModel,
                             @Value("${solesonic.llm.vision.ollama.warmup-on-startup}") boolean warmupOnStartup) {
        this.visionChatModel = visionChatModel;
        this.warmupOnStartup = warmupOnStartup;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmupVisionModelOnStartup() {
        if (!warmupOnStartup) {
            return;
        }

        //Off the startup thread: a cold vision load takes tens of seconds, and the application must
        //be serving requests during it rather than after it.
        CompletableFuture.runAsync(this::warmup);
    }

    private void warmup() {
        long startedAt = System.nanoTime();

        try {
            log.info("Warming up the vision chat model");

            visionChatModel.call(new Prompt(WARMUP_PROMPT));

            log.info("Vision chat model warmed up in {} and kept alive",
                    Duration.ofNanos(System.nanoTime() - startedAt));
        } catch (RuntimeException runtimeException) {
            log.warn("Failed to warm up the vision chat model after {}: {}",
                    Duration.ofNanos(System.nanoTime() - startedAt), runtimeException.getMessage());
        }
    }
}
