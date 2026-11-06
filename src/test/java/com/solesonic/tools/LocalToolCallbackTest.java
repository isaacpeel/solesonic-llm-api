package com.solesonic.tools;

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
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.solesonic.mcp.client.IdentityToolCallback.USER_ID;
import static com.solesonic.mcp.client.IdentityToolCallback.USER_TOKEN;
import static com.solesonic.service.prompt.PromptService.CHAT_ID;
import static com.solesonic.service.prompt.PromptService.REFERENCE_ATTACHMENT_IDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LocalToolCallbackTest {

    private static final String USER_TOKEN_VALUE = "user-token";

    @Mock
    private ToolCallback delegate;

    @Mock
    private JwtDecoder jwtDecoder;

    @Mock
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    private final TestingAuthenticationToken authentication = new TestingAuthenticationToken("user", "credentials");

    private LocalToolCallback localToolCallback;
    private UUID userId;
    private UUID chatId;
    private UUID attachmentId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        chatId = UUID.randomUUID();
        attachmentId = UUID.randomUUID();

        when(delegate.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name("generate_image")
                .description("Generates an image.")
                .inputSchema("{\"type\":\"object\"}")
                .build());
        when(delegate.call(anyString(), any(ToolContext.class))).thenReturn("done");
        when(jwtDecoder.decode(USER_TOKEN_VALUE)).thenReturn(mock(Jwt.class));
        when(jwtAuthenticationConverter.convert(any(Jwt.class))).thenReturn(authentication);

        localToolCallback = new LocalToolCallback(delegate, jwtDecoder, jwtAuthenticationConverter);
    }

    /**
     * Nothing leaves the process, so the ids a local tool needs pass through; only the raw token is
     * kept out of a tool's hands.
     */
    @Test
    void passesTheInternalContextThroughAndKeepsOnlyTheTokenBack() {
        localToolCallback.call("{}", new ToolContext(toolContext()));

        ArgumentCaptor<ToolContext> contextCaptor = ArgumentCaptor.forClass(ToolContext.class);
        verify(delegate).call(anyString(), contextCaptor.capture());

        assertThat(contextCaptor.getValue().getContext())
                .doesNotContainKey(USER_TOKEN)
                .containsEntry(USER_ID, userId)
                .containsEntry(CHAT_ID, chatId)
                .containsEntry(REFERENCE_ATTACHMENT_IDS, Set.of(attachmentId));
    }

    /**
     * A tool gated by {@code @PreAuthorize}, such as {@code uri-ingest}, is checked against the user
     * the turn belongs to, and the identity never outlives the call.
     */
    @Test
    void runsTheToolAsTheUserAndClearsTheIdentityAfterwards() {
        AtomicReference<Authentication> seen = new AtomicReference<>();
        when(delegate.call(anyString(), any(ToolContext.class))).thenAnswer(_ -> {
            seen.set(SecurityContextHolder.getContext().getAuthentication());

            return "done";
        });

        String result = localToolCallback.call("{}", new ToolContext(toolContext()));

        assertThat(result).isEqualTo("done");
        assertThat(seen.get()).isSameAs(authentication);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void refusesACallWithoutAUserToken() {
        ToolContext toolContext = new ToolContext(Map.of(USER_ID, userId));

        assertThatThrownBy(() -> localToolCallback.call("{}", toolContext)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesACallWithoutAToolContext() {
        assertThatThrownBy(() -> localToolCallback.call("{}")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void showsTheModelTheToolsOwnDefinition() {
        assertThat(localToolCallback.getToolDefinition()).isSameAs(delegate.getToolDefinition());
    }

    private Map<String, Object> toolContext() {
        return Map.of(
                USER_TOKEN, USER_TOKEN_VALUE,
                USER_ID, userId,
                CHAT_ID, chatId,
                REFERENCE_ATTACHMENT_IDS, Set.of(attachmentId));
    }
}
