package com.solesonic.service.chat.events;

import com.solesonic.model.chat.ToolActivityEvent;
import com.solesonic.model.chat.ToolCallSummary;
import com.solesonic.service.chat.TurnToolCalls;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

import static com.solesonic.service.prompt.PromptService.CHAT_ID;

/**
 * Tells a chat which tools its turn is calling, on the chat's side channel. The run forwards that
 * channel into the turn's stream as {@code CUSTOM} frames named {@value #TOOL_ACTIVITY}, so private
 * chats and shared conversations both see them, live and on resume.
 * <p>
 * Reporting never gets in the tool's way: a call made without a chat id in its context is run
 * unreported, and a publish that fails or stalls is logged and dropped.
 */
@Component
public class ToolActivityReporter {
    private static final Logger log = LoggerFactory.getLogger(ToolActivityReporter.class);

    public static final String TOOL_ACTIVITY = "tool";

    static final int MAX_ARGUMENTS_LENGTH = 160;

    /**
     * Bounded, because the tool call waits on it. Blocking keeps a call's {@code start} ahead of its
     * {@code end} on the channel.
     */
    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(2);

    private final SideChannelPublisher sideChannelPublisher;
    private final TurnToolCalls turnToolCalls;

    public ToolActivityReporter(SideChannelPublisher sideChannelPublisher, TurnToolCalls turnToolCalls) {
        this.sideChannelPublisher = sideChannelPublisher;
        this.turnToolCalls = turnToolCalls;
    }

    public String report(String toolName, String toolInput, @Nullable ToolContext toolContext, Supplier<String> call) {
        UUID chatId = chatIdOf(toolContext);

        if (chatId == null) {
            return call.get();
        }

        String toolCallId = UUID.randomUUID().toString();
        String arguments = summarize(toolInput);
        long started = System.nanoTime();

        publish(chatId, ToolActivityEvent.started(toolCallId, toolName, arguments));

        try {
            String result = call.get();
            finish(chatId, ToolActivityEvent.ended(toolCallId, toolName, arguments, ToolActivityEvent.OK, elapsedMillis(started)));
            return result;
        } catch (RuntimeException exception) {
            finish(chatId, ToolActivityEvent.ended(toolCallId, toolName, arguments, ToolActivityEvent.ERROR, elapsedMillis(started)));
            throw exception;
        }
    }

    private void finish(UUID chatId, ToolActivityEvent ended) {
        turnToolCalls.record(chatId, ToolCallSummary.of(ended));
        publish(chatId, ended);
    }

    static @Nullable String summarize(@Nullable String toolInput) {
        if (StringUtils.isBlank(toolInput)) {
            return null;
        }

        String compact = StringUtils.normalizeSpace(toolInput);

        return compact.length() <= MAX_ARGUMENTS_LENGTH
                ? compact
                : compact.substring(0, MAX_ARGUMENTS_LENGTH - 1) + "…";
    }

    static @Nullable UUID chatIdOf(@Nullable ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }

        Object chatId = toolContext.getContext().get(CHAT_ID);

        if (chatId instanceof UUID uuid) {
            return uuid;
        }

        if (chatId instanceof String text && StringUtils.isNotBlank(text)) {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }

        return null;
    }

    private void publish(UUID chatId, ToolActivityEvent event) {
        try {
            sideChannelPublisher.publish(chatId, TOOL_ACTIVITY, event).block(PUBLISH_TIMEOUT);
        } catch (RuntimeException exception) {
            log.warn("Could not report {} of tool {} in chat {}: {}", event.phase(), event.name(), chatId, exception.getMessage());
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }
}
