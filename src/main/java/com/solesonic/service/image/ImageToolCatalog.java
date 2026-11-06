package com.solesonic.service.image;

import com.solesonic.model.image.ImageToolSummary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.solesonic.tools.ImageGenerationTool.GENERATE_IMAGE;

/**
 * What {@code GET /images/tools} answers: the one image generator, served through LiteLLM. The image
 * service chooses its workflow from the request, so there is nothing for a caller to choose.
 */
@Service
public class ImageToolCatalog {
    private static final String TITLE = "Generate image";
    private static final String DESCRIPTION = "Generates an image from a text prompt, optionally guided by reference images.";

    private final int maxReferenceImages;

    public ImageToolCatalog(@Value("${solesonic.llm.image.max-reference-images}") int maxReferenceImages) {
        this.maxReferenceImages = maxReferenceImages;
    }

    public List<ImageToolSummary> summaries() {
        return List.of(new ImageToolSummary(GENERATE_IMAGE, TITLE, DESCRIPTION, maxReferenceImages));
    }
}
