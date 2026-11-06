package com.solesonic.mcp.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.solesonic.mcp.client.IdentityToolCallback.USER_ID;
import static com.solesonic.mcp.client.IdentityToolCallback.USER_TOKEN;
import static com.solesonic.service.prompt.PromptService.CHAT_ID;
import static com.solesonic.service.prompt.PromptService.REFERENCE_ATTACHMENT_IDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IdentityToolCallbackTest {

    private static final String MODEL_INPUT = "{\"query\":\"weather in boston\"}";
    private static final String USER_TOKEN_VALUE = "user-token";

    @Mock
    private ToolCallback delegate;

    @Mock
    private JwtDecoder jwtDecoder;

    @Mock
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    private UUID userId;
    private UUID chatId;
    private IdentityToolCallback identityToolCallback;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        chatId = UUID.randomUUID();

        when(delegate.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name("web_search")
                .description("Searches the web.")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}")
                .build());
        when(delegate.getToolMetadata()).thenReturn(ToolMetadata.builder().build());
        when(delegate.call(anyString(), any(ToolContext.class))).thenReturn("[{\"text\":\"sunny\"}]");
        when(jwtDecoder.decode(USER_TOKEN_VALUE)).thenReturn(mock(Jwt.class));
        when(jwtAuthenticationConverter.convert(any(Jwt.class))).thenReturn(new TestingAuthenticationToken("user", "credentials"));

        identityToolCallback = new IdentityToolCallback(delegate, jwtDecoder, jwtAuthenticationConverter);
    }

    /**
     * The tool context becomes the MCP request's {@code _meta}, so nothing internal may reach it.
     */
    @Test
    void call_stripsInternalContextBeforeTheCallLeaves() {
        identityToolCallback.call(MODEL_INPUT, new ToolContext(toolContext()));

        ArgumentCaptor<ToolContext> contextCaptor = ArgumentCaptor.forClass(ToolContext.class);
        verify(delegate).call(eq(MODEL_INPUT), contextCaptor.capture());

        assertThat(contextCaptor.getValue().getContext())
                .doesNotContainKeys(USER_TOKEN, USER_ID, REFERENCE_ATTACHMENT_IDS)
                .containsEntry(CHAT_ID, chatId);
    }

    @Test
    void call_returnsTheToolsResultUntouched() {
        assertThat(identityToolCallback.call(MODEL_INPUT, new ToolContext(toolContext()))).isEqualTo("[{\"text\":\"sunny\"}]");
    }

    @Test
    void getToolDefinition_isTheToolsOwn() {
        assertThat(identityToolCallback.getToolDefinition()).isSameAs(delegate.getToolDefinition());
    }

    @Test
    void call_leavesAToolErrorToSpringAi() {
        ToolExecutionException toolExecutionException = new ToolExecutionException(
                delegate.getToolDefinition(), new IllegalStateException("Error calling tool: rejected"));
        when(delegate.call(anyString(), any(ToolContext.class))).thenThrow(toolExecutionException);

        ToolContext toolContext = new ToolContext(toolContext());

        assertThatThrownBy(() -> identityToolCallback.call(MODEL_INPUT, toolContext)).isSameAs(toolExecutionException);
    }

    @Test
    void call_withoutAUserTokenIsRefused() {
        ToolContext toolContext = new ToolContext(Map.of(CHAT_ID, chatId));

        assertThatThrownBy(() -> identityToolCallback.call(MODEL_INPUT, toolContext)).isInstanceOf(IllegalStateException.class);
    }

    private Map<String, Object> toolContext() {
        return Map.of(
                USER_TOKEN, USER_TOKEN_VALUE,
                USER_ID, userId,
                CHAT_ID, chatId,
                REFERENCE_ATTACHMENT_IDS, Set.of(UUID.randomUUID()));
    }
}
