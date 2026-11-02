package com.solesonic.api.image;

import com.solesonic.exception.handler.ImageGenerationExceptionHandler;
import com.solesonic.exception.handler.LlmKeyExceptionHandler;
import com.solesonic.exception.image.ImageGenerationException;
import com.solesonic.exception.llm.LlmKeyMissingException;
import com.solesonic.model.image.GeneratedImageSummary;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.image.GeneratedImageService;
import com.solesonic.service.image.ImageGenerationService;
import com.solesonic.service.image.ImageToolCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static com.solesonic.model.image.ImageGenerationErrorCode.INVALID_PROMPT;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the generation endpoints' contract, which the move from MCP to LiteLLM must not change.
 */
@ExtendWith(MockitoExtension.class)
class GeneratedImageControllerTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String BODY = "{\"prompt\":\"a lighthouse\",\"referenceAttachmentIds\":[]}";

    @Mock
    private ImageGenerationService imageGenerationService;

    @Mock
    private GeneratedImageService generatedImageService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        UserRequestContext userRequestContext = new UserRequestContext();
        userRequestContext.setUserId(USER_ID);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new GeneratedImageController(imageGenerationService, generatedImageService,
                        new ImageToolCatalog(16), userRequestContext))
                .setControllerAdvice(new ImageGenerationExceptionHandler(), new LlmKeyExceptionHandler())
                .build();
    }

    @Test
    void generateSync_answersCreatedWithTheImagesLocation() throws Exception {
        GeneratedImageSummary generatedImageSummary = summary();
        when(imageGenerationService.generate("a lighthouse", List.of(), USER_ID)).thenReturn(generatedImageSummary);

        mockMvc.perform(post("/images/sync").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", generatedImageSummary.imageUrl()))
                .andExpect(jsonPath("$.imageId").value(generatedImageSummary.imageId().toString()));
    }

    @Test
    void generateSync_forAUserWithNoKeyIsForbidden() throws Exception {
        when(imageGenerationService.generate(any(), any(), any())).thenThrow(new LlmKeyMissingException());

        mockMvc.perform(post("/images/sync").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LLM_KEY_MISSING"));
    }

    @Test
    void generateSync_aFailureLeavesAsItsStatusAndCode() throws Exception {
        when(imageGenerationService.generate(any(), any(), any()))
                .thenThrow(new ImageGenerationException(INVALID_PROMPT, "Describe the image you want."));

        mockMvc.perform(post("/images/sync").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROMPT"));
    }

    @Test
    void generateStreaming_forAUserWithNoKeyIsForbiddenBeforeTheStreamStarts() throws Exception {
        when(imageGenerationService.stream(any(), any(), any())).thenThrow(new LlmKeyMissingException());

        mockMvc.perform(post("/images").accept(MediaType.TEXT_EVENT_STREAM).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void generateStreaming_sendsTheImageAsTheCompleteFrame() throws Exception {
        GeneratedImageSummary generatedImageSummary = summary();
        when(imageGenerationService.stream("a lighthouse", List.of(), USER_ID)).thenReturn(Flux.just(generatedImageSummary));

        MvcResult mvcResult = mockMvc.perform(post("/images").accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:complete")))
                .andExpect(content().string(containsString(generatedImageSummary.imageId().toString())));

        verify(imageGenerationService).stream("a lighthouse", List.of(), USER_ID);
    }

    @Test
    void tools_listsTheOneImageTool() throws Exception {
        mockMvc.perform(get("/images/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("generate_image"))
                .andExpect(jsonPath("$[0].referenceImageSlots").value(16))
                .andExpect(jsonPath("$.length()").value(1));
    }

    private static GeneratedImageSummary summary() {
        UUID imageId = UUID.randomUUID();

        return new GeneratedImageSummary(imageId, USER_ID, null, "/izzybot/images/" + imageId, null, "a lighthouse",
                "solesonic-image (izzy-bot)", null, 1024, 1024, null, 2.0, 8, ZonedDateTime.now(), List.of());
    }
}
