package com.solesonic.model.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseMetadataTest {

    private static final Instant CREATED_AT = Instant.parse("2023-08-04T19:22:45.499127Z");

    /**
     * Mirrors {@code JacksonConfig}, so the round-trip below exercises the same mapper settings the
     * application persists this record with.
     */
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder()
            .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build();

    private static ChatResponse terminalChatResponse() {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage("done"),
                        ChatGenerationMetadata.builder().finishReason("stop").build())),
                ChatResponseMetadata.builder()
                        .model("llama3.2")
                        .keyValue("done", Boolean.TRUE)
                        .keyValue("created-at", CREATED_AT)
                        .keyValue("total-duration", Duration.ofNanos(10706818083L))
                        .keyValue("load-duration", Duration.ofNanos(6338219291L))
                        .keyValue("prompt-eval-count", 26)
                        .keyValue("prompt-eval-duration", Duration.ofNanos(130079000L))
                        .keyValue("eval-count", 259)
                        .keyValue("eval-duration", Duration.ofNanos(4232710000L))
                        .build());
    }

    @Test
    void copiesEveryOllamaReportedFieldVerbatim() {
        ResponseMetadata responseMetadata = ResponseMetadata.from(terminalChatResponse());

        assertThat(responseMetadata.model()).isEqualTo("llama3.2");
        assertThat(responseMetadata.createdAt()).isEqualTo(CREATED_AT);
        assertThat(responseMetadata.doneReason()).isEqualTo("stop");
        assertThat(responseMetadata.totalDurationNanos()).isEqualTo(10706818083L);
        assertThat(responseMetadata.loadDurationNanos()).isEqualTo(6338219291L);
        assertThat(responseMetadata.promptEvalCount()).isEqualTo(26);
        assertThat(responseMetadata.promptEvalDurationNanos()).isEqualTo(130079000L);
        assertThat(responseMetadata.evalCount()).isEqualTo(259);
        assertThat(responseMetadata.evalDurationNanos()).isEqualTo(4232710000L);
    }

    /**
     * A response Ollama never populated must map to nulls rather than throwing — the same record has
     * to survive a model that reports less than the documented set.
     */
    @Test
    void mapsAbsentFieldsToNull() {
        ChatResponse bare = new ChatResponse(
                List.of(new Generation(new AssistantMessage("hi"))),
                ChatResponseMetadata.builder().build());

        ResponseMetadata responseMetadata = ResponseMetadata.from(bare);

        assertThat(responseMetadata.createdAt()).isNull();
        assertThat(responseMetadata.doneReason()).isNull();
        assertThat(responseMetadata.totalDurationNanos()).isNull();
        assertThat(responseMetadata.loadDurationNanos()).isNull();
        assertThat(responseMetadata.promptEvalCount()).isNull();
        assertThat(responseMetadata.promptEvalDurationNanos()).isNull();
        assertThat(responseMetadata.evalCount()).isNull();
        assertThat(responseMetadata.evalDurationNanos()).isNull();
    }

    /**
     * The record is persisted as jsonb, so it has to survive a serialize/deserialize cycle intact —
     * the {@link Instant} in particular, which is the one field whose encoding Jackson decides.
     */
    @Test
    void survivesJacksonRoundTrip() {
        ResponseMetadata responseMetadata = ResponseMetadata.from(terminalChatResponse());

        String json = JSON_MAPPER.writeValueAsString(responseMetadata);
        ResponseMetadata roundTripped = JSON_MAPPER.readValue(json, ResponseMetadata.class);

        assertThat(roundTripped).isEqualTo(responseMetadata);
    }

    /**
     * The values must go back out exactly as Ollama sent them: nanosecond durations as plain
     * integers, and the timestamp in the ISO-8601 form it arrived in rather than a numeric epoch.
     */
    @Test
    void serializesValuesInOllamasOwnForm() {
        String json = JSON_MAPPER.writeValueAsString(ResponseMetadata.from(terminalChatResponse()));
        JsonNode root = JSON_MAPPER.readTree(json);

        assertThat(root.get("totalDurationNanos").isIntegralNumber()).isTrue();
        assertThat(root.get("totalDurationNanos").asLong()).isEqualTo(10706818083L);
        assertThat(root.get("loadDurationNanos").asLong()).isEqualTo(6338219291L);
        assertThat(root.get("promptEvalDurationNanos").asLong()).isEqualTo(130079000L);
        assertThat(root.get("evalDurationNanos").asLong()).isEqualTo(4232710000L);
        assertThat(root.get("promptEvalCount").asLong()).isEqualTo(26L);
        assertThat(root.get("evalCount").asLong()).isEqualTo(259L);

        assertThat(root.get("createdAt").isString()).isTrue();
        assertThat(root.get("createdAt").asString()).isEqualTo("2023-08-04T19:22:45.499127Z");
    }

    /**
     * Rows written before this record changed shape carry fields it no longer declares. Reading one
     * back must not fail — the mapper ignores what it does not recognise.
     */
    @Test
    void ignoresFieldsFromTheOlderPersistedShape() {
        String legacyJson = """
                {"promptTokens":10,"completionTokens":2,"totalTokens":12,\
                "tokensPerSecond":34.7,"timeToFirstTokenMillis":380,"durationMillis":3690}""";

        ResponseMetadata roundTripped = JSON_MAPPER.readValue(legacyJson, ResponseMetadata.class);

        assertThat(roundTripped.promptEvalCount()).isNull();
        assertThat(roundTripped.evalCount()).isNull();
        assertThat(roundTripped.totalDurationNanos()).isNull();
    }
}
