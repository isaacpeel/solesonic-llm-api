package com.solesonic.model.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseMetadataTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-27T19:22:45Z");

    /**
     * Mirrors {@code JacksonConfig}, so the round-trip below exercises the same mapper settings the
     * application persists this record with.
     */
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder()
            .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build();

    private static ModelCallMetadata call(int promptTokens, int completionTokens, double promptMillis, double predictedMillis) {
        return new ModelCallMetadata("qwen3-8b", "chatcmpl-1", CREATED_AT, "stop",
                promptTokens, completionTokens, promptTokens + completionTokens,
                promptMillis, predictedMillis, 61.2,
                null, null, null, null, null, null, null, null, null);
    }

    private static ResponseMetadata singleCallMetadata() {
        return ResponseMetadata.of("qwen3-8b", "chatcmpl-1", CREATED_AT, "stop",
                List.of(call(1042, 259, 130.079, 4232.71)));
    }

    @Test
    void copiesEveryReportedFieldVerbatimForASingleCall() {
        ResponseMetadata responseMetadata = singleCallMetadata();

        assertThat(responseMetadata).isNotNull();
        assertThat(responseMetadata.model()).isEqualTo("qwen3-8b");
        assertThat(responseMetadata.id()).isEqualTo("chatcmpl-1");
        assertThat(responseMetadata.createdAt()).isEqualTo(CREATED_AT);
        assertThat(responseMetadata.finishReason()).isEqualTo("stop");
        assertThat(responseMetadata.modelCalls()).isEqualTo(1);
        assertThat(responseMetadata.promptTokens()).isEqualTo(1042);
        assertThat(responseMetadata.completionTokens()).isEqualTo(259);
        assertThat(responseMetadata.totalTokens()).isEqualTo(1301);
        assertThat(responseMetadata.promptMillis()).isEqualTo(130.079);
        assertThat(responseMetadata.predictedMillis()).isEqualTo(4232.71);
        assertThat(responseMetadata.totalMillis()).isEqualTo(4362.789);
    }

    /**
     * A tool-calling turn runs the model once per tool result. What a client is shown has to be the
     * turn's cost, not the last round trip's, or every tool call under-reports.
     */
    @Test
    void sumsCountsAndDurationsAcrossEveryCallInTheTurn() {
        ResponseMetadata responseMetadata = ResponseMetadata.of("qwen3-8b", "chatcmpl-2", CREATED_AT, "stop",
                List.of(call(1042, 88, 130.0, 900.0),
                        call(1380, 165, 150.5, 2100.25),
                        call(1758, 259, 109.5, 5300.0)));

        assertThat(responseMetadata).isNotNull();
        assertThat(responseMetadata.modelCalls()).isEqualTo(3);
        assertThat(responseMetadata.promptTokens()).isEqualTo(4180);
        assertThat(responseMetadata.completionTokens()).isEqualTo(512);
        assertThat(responseMetadata.totalTokens()).isEqualTo(4692);
        assertThat(responseMetadata.promptMillis()).isEqualTo(390.0);
        assertThat(responseMetadata.predictedMillis()).isEqualTo(8300.25);
        assertThat(responseMetadata.totalMillis()).isEqualTo(8690.25);
    }

    /**
     * The llama.cpp-only counts (cache hits, the server's own prompt/predicted token counts, and
     * speculative-decoding draft stats) sum the same way the portable counts do. Their four sibling
     * rate fields on {@link ModelCallMetadata} have no equivalent on {@link ResponseMetadata} at all —
     * a rate from one round trip does not mean anything added to another's.
     */
    @Test
    void sumsLlamaCppExtraCountsAcrossEveryCallInTheTurn() {
        ModelCallMetadata firstCall = new ModelCallMetadata("qwen3-8b", "chatcmpl-1", CREATED_AT, "tool_calls",
                1042, 88, 1130, 130.0, 900.0, 97.8,
                7, 4, 12.456, 80.284, 227, 5.674, 192, 164, null);
        ModelCallMetadata secondCall = new ModelCallMetadata("qwen3-8b", "chatcmpl-2", CREATED_AT, "stop",
                1380, 165, 1545, 150.5, 2100.25, 78.5,
                3, 9, 16.7, 59.9, 165, 12.7, 200, 171, null);

        ResponseMetadata responseMetadata = ResponseMetadata.of("qwen3-8b", "chatcmpl-2", CREATED_AT, "stop",
                List.of(firstCall, secondCall));

        assertThat(responseMetadata).isNotNull();
        assertThat(responseMetadata.cachedPromptTokens()).isEqualTo(10);
        assertThat(responseMetadata.promptTokensEvaluated()).isEqualTo(13);
        assertThat(responseMetadata.predictedTokensGenerated()).isEqualTo(392);
        assertThat(responseMetadata.draftTokens()).isEqualTo(392);
        assertThat(responseMetadata.draftAcceptedTokens()).isEqualTo(335);
    }

    /**
     * A turn that called no chat model has nothing to report, and must come out as a whole absent
     * record rather than a hollow one full of zeroes.
     */
    @Test
    void isNullWhenNoCallReported() {
        assertThat(ResponseMetadata.of("qwen3-8b", "chatcmpl-3", CREATED_AT, "stop", List.of())).isNull();
    }

    /**
     * A plain OpenAI server sends no timings at all. The counts still have to survive, and the
     * durations have to be absent rather than zero — zero would read as "it took no time".
     */
    @Test
    void leavesUnreportedFieldsNullRatherThanZero() {
        ResponseMetadata responseMetadata = ResponseMetadata.of("gpt-oss", "chatcmpl-4", null, "stop",
                List.of(new ModelCallMetadata("gpt-oss", "chatcmpl-4", null, "stop",
                        10, 2, 12, null, null, null,
                        null, null, null, null, null, null, null, null, null)));

        assertThat(responseMetadata).isNotNull();
        assertThat(responseMetadata.totalTokens()).isEqualTo(12);
        assertThat(responseMetadata.createdAt()).isNull();
        assertThat(responseMetadata.promptMillis()).isNull();
        assertThat(responseMetadata.predictedMillis()).isNull();
        assertThat(responseMetadata.totalMillis()).isNull();
    }

    /**
     * llama.cpp's own timings cover only its generation work, not the network hop to and from it or
     * LiteLLM's own routing overhead — so once a call went through the proxy, its measured duration
     * is the more honest number and wins.
     */
    @Test
    void prefersLiteLlmsMeasuredDurationOverTheServersOwnTimings() {
        ModelCallMetadata proxiedCall = call(1042, 259, 130.079, 4232.71)
                .withLiteLlm(new LiteLlmCallMetadata(
                        "e58baedb-3369-48d3-b889-98df98eb431f",
                        "qwen3.5-9b",
                        "http://izzy-bot:8585/v1",
                        0,
                        0,
                        1930.898,
                        14.345));

        ResponseMetadata responseMetadata = ResponseMetadata.of("auto-model", "chatcmpl-1", CREATED_AT, "stop",
                List.of(proxiedCall));

        assertThat(responseMetadata).isNotNull();
        assertThat(responseMetadata.promptMillis()).isEqualTo(130.079);
        assertThat(responseMetadata.predictedMillis()).isEqualTo(4232.71);
        assertThat(responseMetadata.totalMillis()).isEqualTo(1945.243);
        assertThat(responseMetadata.routedModel()).isEqualTo("qwen3.5-9b");
    }

    /**
     * The record is persisted as jsonb, so it has to survive a serialize/deserialize cycle intact —
     * the {@link Instant} in particular, which is the one field whose encoding Jackson decides.
     */
    @Test
    void survivesJacksonRoundTrip() {
        ResponseMetadata responseMetadata = singleCallMetadata();

        String json = JSON_MAPPER.writeValueAsString(responseMetadata);
        ResponseMetadata roundTripped = JSON_MAPPER.readValue(json, ResponseMetadata.class);

        assertThat(roundTripped).isEqualTo(responseMetadata);
    }

    /**
     * The values must go back out as the server reported them: counts as plain integers, and the
     * timestamp in ISO-8601 form rather than a numeric epoch.
     */
    @Test
    void serializesValuesInTheServersOwnForm() {
        String json = JSON_MAPPER.writeValueAsString(singleCallMetadata());
        JsonNode root = JSON_MAPPER.readTree(json);

        assertThat(root.get("promptTokens").isIntegralNumber()).isTrue();
        assertThat(root.get("promptTokens").asLong()).isEqualTo(1042L);
        assertThat(root.get("completionTokens").asLong()).isEqualTo(259L);
        assertThat(root.get("totalTokens").asLong()).isEqualTo(1301L);
        assertThat(root.get("modelCalls").asLong()).isEqualTo(1L);
        assertThat(root.get("promptMillis").asDouble()).isEqualTo(130.079);
        assertThat(root.get("totalMillis").asDouble()).isEqualTo(4362.789);

        assertThat(root.get("createdAt").isString()).isTrue();
        assertThat(root.get("createdAt").asString()).isEqualTo("2026-08-27T19:22:45Z");
    }

    /**
     * Rows written before this record changed shape carry fields it no longer declares — both the
     * original derived shape and V3_18's predecessor. Reading either back must not fail; V3_20 is what
     * carries their numbers forward, not the mapper.
     */
    @Test
    void ignoresFieldsFromOlderPersistedShapes() {
        String legacyJson = """
                {
                  "promptTokens": 10, "completionTokens": 2, "totalTokens": 12,
                  "tokensPerSecond": 34.7, "timeToFirstTokenMillis": 380, "durationMillis": 3690,
                  "doneReason": "stop", "promptEvalCount": 26, "evalCount": 259,
                  "totalDurationNanos": 10706818083, "loadDurationNanos": 6338219291,
                  "promptEvalDurationNanos": 130079000, "evalDurationNanos": 4232710000
                }""";

        ResponseMetadata roundTripped = JSON_MAPPER.readValue(legacyJson, ResponseMetadata.class);

        assertThat(roundTripped.promptTokens()).isEqualTo(10);
        assertThat(roundTripped.completionTokens()).isEqualTo(2);
        assertThat(roundTripped.totalTokens()).isEqualTo(12);
        assertThat(roundTripped.finishReason()).isNull();
        assertThat(roundTripped.promptMillis()).isNull();
        assertThat(roundTripped.predictedMillis()).isNull();
        assertThat(roundTripped.totalMillis()).isNull();
    }
}
