package com.solesonic.service.image;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.util.JsonHelper;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins down the exact JSON a {@code generate_image} tool result arrives as.
 * <p>
 * {@code SyncMcpToolCallback} hands the tool's content list to {@link JsonHelper#toJson} as a bare
 * {@code Object}, so what the interceptor has to read is whatever Jackson makes of that — not what
 * the MCP wire format says. The two differ, and the difference is what made a working generation
 * look like a result with no image in it.
 */
class GeneratedImageToolResultShapeTest {

    private static final JsonHelper JSON_HELPER = new JsonHelper();

    private static final String BASE64 = Base64.getEncoder().encodeToString("pretend png".getBytes());

    @Test
    void toolResultsAreSerialisedWithoutTheTypeDiscriminator() {
        List<McpSchema.Content> content = List.of(
                new McpSchema.ImageContent(null, BASE64, "image/png", null),
                new McpSchema.TextContent(null, "Generated with FLUX.1-schnell.", null));

        String rawResult = JSON_HELPER.toJson(content);

        assertThat(rawResult).contains(BASE64);
        assertThat(rawResult).contains("image/png");
        assertThat(rawResult).contains("Generated with FLUX.1-schnell.");

        //The record of what this test exists to prove. McpSchema declares the discriminator via
        //@JsonTypeInfo on the Content interface and marks type() @JsonIgnore, so serialising the
        //list as a bare Object loses it — an interceptor keying on "type" finds nothing.
        assertThat(rawResult).doesNotContain("\"type\"");
    }
}
