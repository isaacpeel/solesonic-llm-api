package com.solesonic.service.image;

import com.solesonic.exception.image.ImageGenerationException;
import com.solesonic.mcp.client.McpIdentityProvider;
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

import static com.solesonic.model.image.ImageGenerationErrorCode.NO_IMAGE_TOOLS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImageToolCatalogTest {

    private static final Map<String, Object> PROMPT_ONLY = Map.of(
            "type", "object",
            "properties", Map.of("prompt", Map.of("type", "string")));

    private static final Map<String, Object> WITH_REFERENCES = Map.of(
            "type", "object",
            "properties", Map.of(
                    "prompt", Map.of("type", "string"),
                    "referenceImages", Map.of("type", "array")));

    @Mock
    private McpIdentityProvider mcpIdentityProvider;

    @Mock
    private ChatAttachmentService chatAttachmentService;

    private ReferenceImageInjector referenceImageInjector;

    @BeforeEach
    void setUp() {
        referenceImageInjector = new ReferenceImageInjector(chatAttachmentService, JsonMapper.builder().build());
    }

    @Test
    void summaries_listsTheOneGenerateImageToolFoundByName() {
        when(mcpIdentityProvider.tools()).thenReturn(List.of(
                tool("generate_image", WITH_REFERENCES),
                tool("generate_image_flux", PROMPT_ONLY),
                tool("web_search", PROMPT_ONLY)));

        List<ImageToolSummary> summaries = catalog().summaries();

        assertThat(summaries).extracting(ImageToolSummary::name).containsExactly("generate_image");
        assertThat(summaries).extracting(ImageToolSummary::referenceImageSlots).containsExactly(3);
    }

    @Test
    void resolve_returnsGenerateImage() {
        when(mcpIdentityProvider.tools()).thenReturn(List.of(
                tool("web_search", PROMPT_ONLY),
                tool("generate_image", WITH_REFERENCES)));

        McpSchema.Tool tool = catalog().resolve();

        assertThat(tool.name()).isEqualTo("generate_image");
        assertThat(referenceImageInjector.referenceSlots(tool.inputSchema())).isEqualTo(3);
    }

    @Test
    void withoutGenerateImageThereAreNoImageTools() {
        when(mcpIdentityProvider.tools()).thenReturn(List.of(tool("web_search", PROMPT_ONLY)));

        assertThat(catalog().summaries()).isEmpty();
        assertThatThrownBy(() -> catalog().resolve())
                .isInstanceOfSatisfying(ImageGenerationException.class, imageGenerationException ->
                        assertThat(imageGenerationException.getErrorCode()).isEqualTo(NO_IMAGE_TOOLS));
    }

    private ImageToolCatalog catalog() {
        return new ImageToolCatalog(mcpIdentityProvider, referenceImageInjector);
    }

    private static McpSchema.Tool tool(String name, Map<String, Object> inputSchema) {
        return McpSchema.Tool.builder(name, inputSchema).title(name).description("A tool.").build();
    }
}
