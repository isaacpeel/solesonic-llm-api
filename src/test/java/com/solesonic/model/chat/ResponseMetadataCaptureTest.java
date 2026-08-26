package com.solesonic.model.chat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseMetadataCaptureTest {

    private static ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder().build());
    }

    private static ChatResponse terminalChunk() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(""))),
                ChatResponseMetadata.builder()
                        .keyValue("done", Boolean.TRUE)
                        .keyValue("eval-count", 259)
                        .build());
    }

    @Test
    void capturesNothingBeforeOllamaReportsDone() {
        ResponseMetadataCapture responseMetadataCapture = new ResponseMetadataCapture();

        responseMetadataCapture.accept(chunk("hel"));
        responseMetadataCapture.accept(chunk("lo"));

        assertThat(responseMetadataCapture.metadata()).isNull();
    }

    @Test
    void capturesTheTerminalResponse() {
        ResponseMetadataCapture responseMetadataCapture = new ResponseMetadataCapture();

        responseMetadataCapture.accept(chunk("hello"));
        responseMetadataCapture.accept(terminalChunk());

        ResponseMetadata responseMetadata = responseMetadataCapture.metadata();

        assertThat(responseMetadata).isNotNull();
        assertThat(responseMetadata.evalCount()).isEqualTo(259);
    }

    /**
     * A chunk carrying no numbers must never overwrite the ones already captured, or a stray trailing
     * chunk would blank the turn's accounting.
     */
    @Test
    void laterEmptyChunkDoesNotClobberCapturedMetadata() {
        ResponseMetadataCapture responseMetadataCapture = new ResponseMetadataCapture();

        responseMetadataCapture.accept(terminalChunk());
        responseMetadataCapture.accept(chunk("trailing"));

        ResponseMetadata responseMetadata = responseMetadataCapture.metadata();

        assertThat(responseMetadata).isNotNull();
        assertThat(responseMetadata.evalCount()).isEqualTo(259);
    }
}
