package com.solesonic.service.image;

import com.solesonic.exception.image.ImageGenerationException;
import com.solesonic.mcp.client.McpIdentityProvider;
import com.solesonic.model.image.ImageGenerationErrorCode;
import com.solesonic.model.image.ImageToolSummary;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static com.solesonic.model.image.ImageGenerationErrorCode.IMAGE_TOOL_REQUIRED;
import static com.solesonic.model.image.ImageGenerationErrorCode.INVALID_IMAGE_TOOL;
import static com.solesonic.model.image.ImageGenerationErrorCode.NO_IMAGE_TOOLS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImageToolCatalogTest {

    private static final Map<String, Object> IMAGE_META = Map.of("solesonic/kind", "image-generation");

    private static final Map<String, Object> PROMPT_ONLY = Map.of(
            "type", "object",
            "properties", Map.of("prompt", Map.of("type", "string")));

    private static final Map<String, Object> ONE_SLOT = Map.of(
            "type", "object",
            "properties", Map.of(
                    "prompt", Map.of("type", "string"),
                    "reference_images", Map.of("type", "array", "minItems", 1, "maxItems", 1)));

    @Mock
    private McpIdentityProvider mcpIdentityProvider;

    @Mock
    private ChatAttachmentService chatAttachmentService;

    private ReferenceImageInjector referenceImageInjector;

    @BeforeEach
    void setUp() {
        referenceImageInjector = new ReferenceImageInjector(chatAttachmentService, JsonMapper.builder().build());
    }

    /**
     * The tag decides, not the name: a tagged row called anything is an image tool, and an untagged
     * tool that merely sounds like one is not.
     */
    @Test
    void summaries_listsOnlyTaggedToolsWithTheirSlotCounts() {
        when(mcpIdentityProvider.tools()).thenReturn(List.of(
                tagged("flux_schnell", PROMPT_ONLY),
                tagged("kontext_edit", ONE_SLOT),
                untagged("generate_image"),
                untagged("web_search")));

        List<ImageToolSummary> summaries = catalog().summaries();

        assertThat(summaries).extracting(ImageToolSummary::name).containsExactly("flux_schnell", "kontext_edit");
        assertThat(summaries).extracting(ImageToolSummary::referenceImageSlots).containsExactly(0, 1);
        assertThat(catalog().toolNames()).containsExactly("flux_schnell", "kontext_edit");
    }

    /**
     * No workflow configured means no image tools — not a tool called by a remembered name.
     */
    @Test
    void withNoWorkflowsThereAreNoImageTools() {
        when(mcpIdentityProvider.tools()).thenReturn(List.of(untagged("generate_image"), untagged("web_search")));

        assertThat(catalog().summaries()).isEmpty();
        assertThat(catalog().toolNames()).isEmpty();
        assertRefusedWith(null, NO_IMAGE_TOOLS);
        assertRefusedWith("generate_image", NO_IMAGE_TOOLS);
    }

    @Test
    void resolve_withASingleImageToolTheToolMayBeLeftOut() {
        when(mcpIdentityProvider.tools()).thenReturn(List.of(tagged("flux_schnell", PROMPT_ONLY), untagged("web_search")));

        assertThat(catalog().resolve(null).name()).isEqualTo("flux_schnell");
        assertThat(catalog().resolve(" ").name()).isEqualTo("flux_schnell");
    }

    @Test
    void resolve_withSeveralImageToolsTheCallerMustChoose() {
        when(mcpIdentityProvider.tools()).thenReturn(List.of(
                tagged("flux_schnell", PROMPT_ONLY),
                tagged("kontext_edit", ONE_SLOT)));

        assertRefusedWith(null, IMAGE_TOOL_REQUIRED);

        McpSchema.Tool tool = catalog().resolve("kontext_edit");
        assertThat(tool.name()).isEqualTo("kontext_edit");
        assertThat(referenceImageInjector.referenceSlots(tool.inputSchema())).isEqualTo(1);
    }

    /**
     * A caller can name only an image tool: the endpoint must not become a way to call an arbitrary
     * MCP tool on the user's token.
     */
    @Test
    void resolve_aToolThatIsNotAnImageToolIsRefused() {
        when(mcpIdentityProvider.tools()).thenReturn(List.of(
                tagged("flux_schnell", PROMPT_ONLY),
                untagged("delete_jira_issue")));

        assertRefusedWith("delete_jira_issue", INVALID_IMAGE_TOOL);
    }

    private void assertRefusedWith(String requestedTool, ImageGenerationErrorCode errorCode) {
        assertThatThrownBy(() -> catalog().resolve(requestedTool))
                .isInstanceOfSatisfying(ImageGenerationException.class, imageGenerationException ->
                        assertThat(imageGenerationException.getErrorCode()).isEqualTo(errorCode));
    }

    private ImageToolCatalog catalog() {
        return new ImageToolCatalog(mcpIdentityProvider, referenceImageInjector);
    }

    private static McpSchema.Tool tagged(String name, Map<String, Object> inputSchema) {
        return McpSchema.Tool.builder(name, inputSchema).title(name).description("Creates an image.").meta(IMAGE_META).build();
    }

    private static McpSchema.Tool untagged(String name) {
        return McpSchema.Tool.builder(name, PROMPT_ONLY).description("Something else.").build();
    }
}
