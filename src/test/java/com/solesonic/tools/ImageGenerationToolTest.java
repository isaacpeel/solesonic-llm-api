package com.solesonic.tools;

import com.solesonic.exception.image.ImageGenerationException;
import com.solesonic.model.image.GeneratedImageSummary;
import com.solesonic.service.chat.events.NotificationService;
import com.solesonic.service.image.ImageGenerationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.solesonic.mcp.client.IdentityToolCallback.USER_ID;
import static com.solesonic.model.image.ImageGenerationErrorCode.INVALID_REFERENCE_IMAGE;
import static com.solesonic.service.prompt.PromptService.CHAT_ID;
import static com.solesonic.service.prompt.PromptService.REFERENCE_ATTACHMENT_IDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageGenerationToolTest {

    @Mock
    private ImageGenerationService imageGenerationService;

    @Mock
    private NotificationService notificationService;

    private ImageGenerationTool imageGenerationTool;
    private UUID userId;
    private UUID chatId;
    private UUID attachmentId;

    @BeforeEach
    void setUp() {
        imageGenerationTool = new ImageGenerationTool(imageGenerationService, notificationService);
        userId = UUID.randomUUID();
        chatId = UUID.randomUUID();
        attachmentId = UUID.randomUUID();
    }

    @Test
    void generatesAgainstTheChatWithTheSendsReferenceImages() {
        GeneratedImageSummary generatedImageSummary = summary();
        when(imageGenerationService.generateInChat("a lighthouse", List.of(attachmentId), userId, chatId))
                .thenReturn(generatedImageSummary);

        imageGenerationTool.generateImage("a lighthouse", new ToolContext(toolContext()));

        verify(imageGenerationService).generateInChat("a lighthouse", List.of(attachmentId), userId, chatId);
    }

    /**
     * The client hears about the image before the model writes a word, and again on the turn's
     * terminal frame.
     */
    @Test
    void announcesTheImageOnTheChatsSideChannel() {
        GeneratedImageSummary generatedImageSummary = summary();
        when(imageGenerationService.generateInChat(any(), any(), any(), any())).thenReturn(generatedImageSummary);

        imageGenerationTool.generateImage("a lighthouse", new ToolContext(toolContext()));

        verify(notificationService).emitGeneratedImage(chatId, generatedImageSummary);
    }

    /**
     * Without the last instruction a model told only that an image exists will describe one anyway.
     */
    @Test
    void tellsTheModelTheImageIdAndThatItCannotSeeTheImage() {
        GeneratedImageSummary generatedImageSummary = summary();
        when(imageGenerationService.generateInChat(any(), any(), any(), any())).thenReturn(generatedImageSummary);

        String modelText = imageGenerationTool.generateImage("a lighthouse", new ToolContext(toolContext()));

        assertThat(modelText)
                .contains(generatedImageSummary.imageId().toString())
                .contains("already displayed to the user")
                .contains("You cannot see this image")
                .doesNotContain("base64")
                .hasSizeLessThan(1000);
    }

    @Test
    void aFailureIsExplainedToTheModelInTheApisOwnWords() {
        when(imageGenerationService.generateInChat(any(), any(), any(), any()))
                .thenThrow(new ImageGenerationException(INVALID_REFERENCE_IMAGE,
                        "No image style accepts that many reference images. Try again with a different number."));

        String modelText = imageGenerationTool.generateImage("p", new ToolContext(toolContext()));

        assertThat(modelText)
                .contains("could not be generated")
                .contains("No image style accepts that many reference images");
        verifyNoInteractions(notificationService);
    }

    @Test
    void anUnexpectedFailureNeverReachesTheModelVerbatim() {
        when(imageGenerationService.generateInChat(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("db at 10.0.0.9 down"));

        String modelText = imageGenerationTool.generateImage("p", new ToolContext(toolContext()));

        assertThat(modelText).contains("could not be generated").doesNotContain("10.0.0.9");
    }

    /**
     * Every read is user-scoped, so an image with no owner could never be served back.
     */
    @Test
    void withoutAUserNothingIsGenerated() {
        String modelText = imageGenerationTool.generateImage("p", new ToolContext(Map.of(CHAT_ID, chatId)));

        assertThat(modelText).contains("could not be generated");
        verify(imageGenerationService, never()).generateInChat(any(), any(), any(), any());
    }

    @Test
    void withoutReferenceIdsGeneratesFromThePromptAlone() {
        when(imageGenerationService.generateInChat(any(), any(), any(), any())).thenReturn(summary());

        imageGenerationTool.generateImage("p", new ToolContext(Map.of(USER_ID, userId, CHAT_ID, chatId)));

        verify(imageGenerationService).generateInChat("p", List.of(), userId, chatId);
    }

    /**
     * The model is offered a prompt and nothing else: no parameter it could put image data in.
     */
    @Test
    void theModelFacingDefinitionTakesOnlyAPrompt() {
        ToolCallback[] toolCallbacks = ToolCallbacks.from(imageGenerationTool);

        assertThat(toolCallbacks).singleElement().satisfies(toolCallback -> {
            assertThat(toolCallback.getToolDefinition().name()).isEqualTo(ImageGenerationTool.GENERATE_IMAGE);
            assertThat(toolCallback.getToolDefinition().inputSchema())
                    .contains("\"prompt\"")
                    .doesNotContain("referenceImages")
                    .doesNotContain("toolContext");
        });
    }

    private Map<String, Object> toolContext() {
        return Map.of(
                USER_ID, userId,
                CHAT_ID, chatId,
                REFERENCE_ATTACHMENT_IDS, new LinkedHashSet<>(List.of(attachmentId)));
    }

    private GeneratedImageSummary summary() {
        return new GeneratedImageSummary(UUID.randomUUID(), userId, null, "/images/x", null, "a lighthouse",
                "solesonic-image (izzy-bot)", null, 1024, 1024, null, 2.0, 8, ZonedDateTime.now(), List.of());
    }
}
