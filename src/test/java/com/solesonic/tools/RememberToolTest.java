package com.solesonic.tools;

import com.solesonic.model.memory.MemoryKind;
import com.solesonic.model.memory.MemoryOrigin;
import com.solesonic.model.memory.MemoryStatus;
import com.solesonic.model.memory.UserMemorySummary;
import com.solesonic.service.memory.UserMemoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.solesonic.service.prompt.PromptService.CHAT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RememberToolTest {
    private final UUID chatId = UUID.randomUUID();

    @Mock
    private UserMemoryService userMemoryService;

    /**
     * The conversation comes from the server-side tool context. Nothing in the tool's arguments can
     * name a user: there is no such argument, and one the model invents is ignored.
     */
    @Test
    void remembersForTheToolContextsChatIgnoringAnyUserTheArgumentsName() {
        when(userMemoryService.remember(chatId, MemoryKind.PREFERENCE, "units.preference", "Prefers metric units"))
                .thenReturn(summary());

        ToolCallback toolCallback = ToolCallbacks.from(new RememberTool(userMemoryService))[0];

        String forgedUserId = UUID.randomUUID().toString();
        String input = "{'request':{'kind':'PREFERENCE','key':'units.preference','content':'Prefers metric units',"
                + "'userId':'" + forgedUserId + "'},'userId':'" + forgedUserId + "'}";

        toolCallback.call(input.replace('\'', '"'), new ToolContext(Map.of(CHAT_ID, chatId)));

        verify(userMemoryService).remember(chatId, MemoryKind.PREFERENCE, "units.preference", "Prefers metric units");
    }

    @Test
    void refusesWithoutAChatInTheToolContext() {
        RememberTool rememberTool = new RememberTool(userMemoryService);

        assertThatThrownBy(() -> rememberTool.remember(
                new RememberTool.RememberRequest(MemoryKind.FACT, null, "Has two kids"), new ToolContext(Map.of())))
                .isInstanceOf(IllegalStateException.class);

        verify(userMemoryService, never()).remember(any(), any(), any(), any());
    }

    @Test
    void isNamedRememberSoItAppearsAsASlashCommand() {
        ToolCallback toolCallback = ToolCallbacks.from(new RememberTool(userMemoryService))[0];

        assertThat(toolCallback.getToolDefinition().name()).isEqualTo(RememberTool.REMEMBER);
        verify(userMemoryService, never()).remember(eq(chatId), any(), any(), any());
    }

    private UserMemorySummary summary() {
        ZonedDateTime now = ZonedDateTime.now();

        return new UserMemorySummary(UUID.randomUUID(), MemoryKind.PREFERENCE, MemoryOrigin.REMEMBERED, MemoryStatus.ACTIVE,
                "Prefers metric units", 1.0, now, now, now, List.of(chatId));
    }
}
