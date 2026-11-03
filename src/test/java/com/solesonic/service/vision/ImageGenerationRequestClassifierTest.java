package com.solesonic.service.vision;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageGenerationRequestClassifierTest {

    @Mock
    private OpenAiChatModel decisionChatModel;

    private ImageGenerationRequestClassifier imageGenerationRequestClassifier;

    @BeforeEach
    void setUp() {
        imageGenerationRequestClassifier = new ImageGenerationRequestClassifier(decisionChatModel,
                new ClassPathResource("prompts/image-generation-request-prompt.st"), "decision-model");
    }

    private void decisionModelAnswers(String answer) {
        when(decisionChatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(answer)))));
    }

    @Test
    void aYesIsAnImageGenerationRequest() {
        decisionModelAnswers("YES");

        assertThat(imageGenerationRequestClassifier.requestsImageGeneration("create a picture of this person relaxing on a beach")).isTrue();
    }

    @Test
    void aNoIsNot() {
        decisionModelAnswers("NO");

        assertThat(imageGenerationRequestClassifier.requestsImageGeneration("what is in this photo?")).isFalse();
    }

    @Test
    void toleratesPunctuationAndCase() {
        decisionModelAnswers(" yes.\\n");

        assertThat(imageGenerationRequestClassifier.requestsImageGeneration("turn this into a cartoon")).isTrue();
    }

    /**
     * The user's words go to the decision model as their own message, under the instruction, so
     * nothing in them reads as part of the instruction.
     */
    @Test
    void sendsTheInstructionAsTheSystemMessageAndTheUsersWordsAsTheirOwn() {
        decisionModelAnswers("YES");

        imageGenerationRequestClassifier.requestsImageGeneration("make it a watercolor");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(decisionChatModel).call(promptCaptor.capture());

        assertThat(promptCaptor.getValue().getInstructions())
                .extracting(Message::getMessageType)
                .containsExactly(MessageType.SYSTEM, MessageType.USER);
        assertThat(promptCaptor.getValue().getInstructions().get(1).getText()).isEqualTo("make it a watercolor");
    }

    /**
     * Options on the prompt replace the model's configured defaults wholesale, the model name among
     * them, which reached the server as "Invalid model name passed in" and quietly made every request
     * a no.
     */
    @Test
    void sendsNoOptionsSoTheConfiguredModelApplies() {
        decisionModelAnswers("YES");

        imageGenerationRequestClassifier.requestsImageGeneration("make it a watercolor");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(decisionChatModel).call(promptCaptor.capture());

        assertThat(promptCaptor.getValue().getOptions()).isNull();
    }

    @Test
    void anythingButAClearYesIsNo() {
        decisionModelAnswers("Probably yes, it seems so");

        assertThat(imageGenerationRequestClassifier.requestsImageGeneration("hmm")).isFalse();
    }

    @Test
    void aFailedCallIsNo() {
        when(decisionChatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("model down"));

        assertThat(imageGenerationRequestClassifier.requestsImageGeneration("make it a watercolor")).isFalse();
    }

    @Test
    void aMessageWithNoWordsIsNoWithoutAsking() {
        assertThat(imageGenerationRequestClassifier.requestsImageGeneration("  ")).isFalse();

        verifyNoInteractions(decisionChatModel);
    }
}
