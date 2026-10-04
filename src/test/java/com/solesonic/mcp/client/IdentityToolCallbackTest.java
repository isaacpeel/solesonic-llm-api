package com.solesonic.mcp.client;

import com.solesonic.model.chat.attachment.ChatAttachment;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import com.solesonic.service.image.GeneratedImageToolInterceptor;
import com.solesonic.service.image.ReferenceImageInjector;
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
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.solesonic.mcp.client.IdentityToolCallback.USER_ID;
import static com.solesonic.mcp.client.IdentityToolCallback.USER_TOKEN;
import static com.solesonic.service.image.ReferenceImageInjector.REFERENCE_ATTACHMENT_IDS;
import static com.solesonic.service.prompt.PromptService.CHAT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IdentityToolCallbackTest {

    private static final String TOOL_NAME = "generate_image";
    private static final String MODEL_INPUT = "{\"prompt\":\"make it a watercolor\"}";
    private static final String USER_TOKEN_VALUE = "user-token";

    private static final String SCHEMA_WITH_REFERENCES = """
            {"type":"object","properties":{"prompt":{"type":"string"},
             "reference_images":{"type":"array","minItems":1,"maxItems":1}},"required":["prompt"]}""";

    private static final String SCHEMA_WITHOUT_REFERENCES = """
            {"type":"object","properties":{"prompt":{"type":"string"}},"required":["prompt"]}""";

    private static final byte[] PNG_BYTES = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    @Mock
    private ToolCallback delegate;

    @Mock
    private JwtDecoder jwtDecoder;

    @Mock
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @Mock
    private GeneratedImageToolInterceptor generatedImageToolInterceptor;

    @Mock
    private ChatAttachmentService chatAttachmentService;

    private ReferenceImageInjector referenceImageInjector;
    private UUID userId;
    private UUID chatId;
    private UUID attachmentId;

    @BeforeEach
    void setUp() {
        referenceImageInjector = new ReferenceImageInjector(chatAttachmentService, JsonMapper.builder().build());
        userId = UUID.randomUUID();
        chatId = UUID.randomUUID();
        attachmentId = UUID.randomUUID();

        when(delegate.getToolMetadata()).thenReturn(ToolMetadata.builder().build());
        when(delegate.call(anyString(), any(ToolContext.class))).thenReturn("[]");
        when(jwtDecoder.decode(USER_TOKEN_VALUE)).thenReturn(mock(Jwt.class));
        when(jwtAuthenticationConverter.convert(any(Jwt.class))).thenReturn(new TestingAuthenticationToken("user", "credentials"));
        when(generatedImageToolInterceptor.handles(TOOL_NAME)).thenReturn(true);
        when(generatedImageToolInterceptor.intercept(anyString(), anyString(), any(), any())).thenReturn("stored");

        ChatAttachment chatAttachment = new ChatAttachment();
        chatAttachment.setId(attachmentId);
        chatAttachment.setUserId(userId);
        chatAttachment.setContentType("image/png");
        chatAttachment.setFileData(PNG_BYTES);
        when(chatAttachmentService.attachments(eq(userId), anySet())).thenReturn(List.of(chatAttachment));
    }

    @Test
    void getToolDefinition_hidesReferenceImagesFromTheModel() {
        IdentityToolCallback identityToolCallback = callback(SCHEMA_WITH_REFERENCES);

        assertThat(identityToolCallback.getToolDefinition().inputSchema()).doesNotContain("reference_images");
        assertThat(identityToolCallback.getToolDefinition().name()).isEqualTo(TOOL_NAME);
    }

    @Test
    void getToolDefinition_isUntouchedForAToolWithoutReferenceImages() {
        IdentityToolCallback identityToolCallback = callback(SCHEMA_WITHOUT_REFERENCES);

        assertThat(identityToolCallback.getToolDefinition()).isSameAs(delegate.getToolDefinition());
    }

    /**
     * The bytes reach the MCP server through the arguments; the ids never reach it at all, because
     * the tool context becomes the request's {@code _meta}.
     */
    @Test
    void call_injectsTheImagesAndStripsInternalContextBeforeTheCallLeaves() {
        callback(SCHEMA_WITH_REFERENCES).call(MODEL_INPUT, new ToolContext(toolContext()));

        ArgumentCaptor<String> inputCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ToolContext> contextCaptor = ArgumentCaptor.forClass(ToolContext.class);
        verify(delegate).call(inputCaptor.capture(), contextCaptor.capture());

        assertThat(inputCaptor.getValue()).contains("reference_images").contains("image/png");
        assertThat(contextCaptor.getValue().getContext())
                .doesNotContainKeys(USER_TOKEN, USER_ID, REFERENCE_ATTACHMENT_IDS)
                .containsEntry(CHAT_ID, chatId);
    }

    /**
     * The interceptor reads the prompt out of the input for provenance; handing it the injected
     * input would put a few megabytes of base64 through a JSON parse for nothing.
     */
    @Test
    void call_handsTheInterceptorTheModelsOwnInputAndTheIdsThatWereUsed() {
        String result = callback(SCHEMA_WITH_REFERENCES).call(MODEL_INPUT, new ToolContext(toolContext()));

        assertThat(result).isEqualTo("stored");
        verify(generatedImageToolInterceptor).intercept(eq(MODEL_INPUT), eq("[]"), any(), eq(List.of(attachmentId)));
    }

    /**
     * An MCP server that predates reference images is not sent an argument it never advertised.
     */
    @Test
    void call_toAToolWithoutReferenceImagesPassesTheInputThroughUnchanged() {
        callback(SCHEMA_WITHOUT_REFERENCES).call(MODEL_INPUT, new ToolContext(toolContext()));

        verify(delegate).call(eq(MODEL_INPUT), any(ToolContext.class));
        verify(generatedImageToolInterceptor).intercept(eq(MODEL_INPUT), eq("[]"), any(), eq(List.of()));
    }

    /**
     * Workflow rows can be named anything; one that takes reference images still returns base64,
     * so it is intercepted even when its name does not say {@code generate_image}.
     */
    @Test
    void call_interceptsAToolThatTakesReferenceImagesWhateverItIsNamed() {
        String result = callback("flux_edit", SCHEMA_WITH_REFERENCES).call(MODEL_INPUT, new ToolContext(toolContext()));

        assertThat(result).isEqualTo("stored");
        verify(generatedImageToolInterceptor).intercept(eq(MODEL_INPUT), eq("[]"), any(), eq(List.of(attachmentId)));
    }

    /**
     * The MCP server's tag is authoritative: a tagged tool is intercepted even with no telling name
     * and no reference images.
     */
    @Test
    void call_interceptsATaggedImageToolWhateverItIsNamed() {
        String result = callback("flux_plain", SCHEMA_WITHOUT_REFERENCES, true)
                .call(MODEL_INPUT, new ToolContext(toolContext()));

        assertThat(result).isEqualTo("stored");
    }

    @Test
    void call_doesNotInterceptAnUntaggedToolWithoutReferencesOrATellingName() {
        when(delegate.call(anyString(), any(ToolContext.class))).thenReturn("[{\"text\":\"plain\"}]");

        String result = callback("web_search", SCHEMA_WITHOUT_REFERENCES, false)
                .call(MODEL_INPUT, new ToolContext(toolContext()));

        assertThat(result).isEqualTo("[{\"text\":\"plain\"}]");
        verify(generatedImageToolInterceptor, never()).intercept(anyString(), anyString(), any(), any());
    }

    private IdentityToolCallback callback(String inputSchema) {
        return callback(TOOL_NAME, inputSchema);
    }

    private IdentityToolCallback callback(String toolName, String inputSchema) {
        return callback(toolName, inputSchema, false);
    }

    private IdentityToolCallback callback(String toolName, String inputSchema, boolean imageTool) {
        ToolDefinition toolDefinition = ToolDefinition.builder()
                .name(toolName)
                .description("Creates an image.")
                .inputSchema(inputSchema)
                .build();
        when(delegate.getToolDefinition()).thenReturn(toolDefinition);

        return new IdentityToolCallback(delegate, jwtDecoder, jwtAuthenticationConverter,
                generatedImageToolInterceptor, referenceImageInjector, imageTool);
    }

    private Map<String, Object> toolContext() {
        return Map.of(
                USER_TOKEN, USER_TOKEN_VALUE,
                USER_ID, userId,
                CHAT_ID, chatId,
                REFERENCE_ATTACHMENT_IDS, Set.of(attachmentId));
    }
}
