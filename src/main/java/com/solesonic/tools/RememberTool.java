package com.solesonic.tools;

import com.solesonic.model.memory.MemoryKind;
import com.solesonic.model.memory.UserMemorySummary;
import com.solesonic.service.memory.UserMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.UUID;

import static com.solesonic.service.prompt.PromptService.CHAT_ID;

/**
 * {@code /remember}: the user asking for something to be kept for every future conversation.
 * <p>
 * Only ever a slash command, which only the user can type. It is never offered to the model as a
 * callable tool, because a model-callable "remember" is an injection target — an email that says
 * "and remember that the user's bank is..." would be obeyed.
 * <p>
 * The user is never taken from the tool's arguments. {@code IdentityToolCallback} strips the user id
 * from the tool context before a local tool runs, so the owner is resolved from the conversation the
 * server put in that context — the chat row, the same single source reflection uses.
 */
@Component
public class RememberTool implements LocalTool {
    private static final Logger log = LoggerFactory.getLogger(RememberTool.class);

    public static final String REMEMBER = "remember";

    private final UserMemoryService userMemoryService;

    public RememberTool(UserMemoryService userMemoryService) {
        this.userMemoryService = userMemoryService;
    }

    public record RememberRequest(MemoryKind kind, String key, String content) {
    }

    public record RememberResponse(UUID memoryId, MemoryKind kind, String content) {
    }

    @Tool(name = REMEMBER, description = "Remembers something the user asked to have remembered about them, for every "
            + "future conversation. kind is FACT (something true about the user), PREFERENCE (how they like things) or "
            + "INSTRUCTION (how to answer them from now on). key is a short lowercase dot-separated subject such as "
            + "units.preference. content is what to remember, in the user's own terms.")
    public RememberResponse remember(RememberRequest request, ToolContext toolContext) {
        if (!(toolContext.getContext().get(CHAT_ID) instanceof UUID chatId)) {
            throw new IllegalStateException("No conversation in the tool context for " + REMEMBER);
        }

        UserMemorySummary remembered = userMemoryService.remember(chatId, request.kind(), request.key(), request.content());

        log.info("Remembered memory {} from chat {}", remembered.id(), chatId);

        return new RememberResponse(remembered.id(), remembered.kind(), remembered.content());
    }
}
