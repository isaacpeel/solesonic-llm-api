package com.solesonic.model.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Round-trips the per-call breakdown the way Hibernate persists it, which is <em>not</em> the way
 * {@code ResponseMetadataTest} does.
 * <p>
 * {@code chat_message.response_metadata_calls} is written through Hibernate's own
 * {@code JacksonJsonFormatMapper}, a plain Jackson mapper that — unlike the application's, which
 * {@code JacksonConfig} configures to ignore unknown properties — fails on any field it cannot map
 * back onto a record component. A derived accessor shaped like a bean getter is therefore a
 * persistence bug rather than noise on the wire: it is written on update and rejected by the
 * read-back Hibernate performs while committing.
 */
class ModelCallMetadataPersistenceTest {

    private static final ObjectMapper HIBERNATE_STYLE_MAPPER = new ObjectMapper();

    private static ModelCallMetadata callWithProxyHeaders() {
        return new ModelCallMetadata(
                "auto-model",
                "chatcmpl-1",
                null,
                "stop",
                11,
                592,
                603,
                97.957,
                5215.708,
                69.597,
                new LiteLlmCallMetadata(
                        "e58baedb-3369-48d3-b889-98df98eb431f",
                        "qwen3.5-9b",
                        "http://izzy-bot-spark:8585/v1",
                        0,
                        0));
    }

    @Test
    void survivesTheRoundTripHibernatePerformsWhileCommitting() throws JsonProcessingException {
        String json = HIBERNATE_STYLE_MAPPER.writeValueAsString(List.of(callWithProxyHeaders()));

        assertThatCode(() -> HIBERNATE_STYLE_MAPPER.readValue(json, new TypeReference<List<ModelCallMetadata>>() {
        })).doesNotThrowAnyException();
    }

    @Test
    void keepsEveryProxyFieldThroughTheRoundTrip() throws JsonProcessingException {
        String json = HIBERNATE_STYLE_MAPPER.writeValueAsString(List.of(callWithProxyHeaders()));

        List<ModelCallMetadata> roundTripped = HIBERNATE_STYLE_MAPPER.readValue(json, new TypeReference<>() {
        });

        assertThat(roundTripped).singleElement().isEqualTo(callWithProxyHeaders());
    }

    /**
     * The specific shape that broke it: a no-argument {@code isX()} is written by Jackson as a
     * property {@code x}, which no record component can read back.
     */
    @Test
    void writesNoFieldThatIsNotARecordComponent() throws JsonProcessingException {
        String json = HIBERNATE_STYLE_MAPPER.writeValueAsString(callWithProxyHeaders().liteLlm());

        assertThat(json).doesNotContain("present");
        assertThat(HIBERNATE_STYLE_MAPPER.readTree(json).properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("callId", "modelName", "modelApiBase",
                        "attemptedRetries", "attemptedFallbacks");
    }
}
