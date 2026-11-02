package com.solesonic.service.image;

import com.solesonic.model.image.ImageToolSummary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImageToolCatalogTest {

    /**
     * {@code GET /images/tools} is part of the API contract, so it still answers one entry, now
     * describing the LiteLLM-served generator rather than an MCP tool.
     */
    @Test
    void summaries_listsTheOneImageToolWithTheConfiguredReferenceLimit() {
        List<ImageToolSummary> summaries = new ImageToolCatalog(16).summaries();

        assertThat(summaries).singleElement().satisfies(summary -> {
            assertThat(summary.name()).isEqualTo("generate_image");
            assertThat(summary.title()).isNotBlank();
            assertThat(summary.description()).isNotBlank();
            assertThat(summary.referenceImageSlots()).isEqualTo(16);
        });
    }
}
