package com.solesonic.service.litellm;

import com.solesonic.model.chat.LiteLlmCallMetadata;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Takes the {@code x-litellm-*} headers off every OpenAI-bound response and files them under the
 * correlation id the request carried.
 * <p>
 * Registered through Spring AI's {@code OpenAiHttpClientBuilderCustomizer}, which is the supported
 * way to reach the OkHttp client behind the autoconfigured chat model.
 * <p>
 * The response body is never touched. A streamed completion is an open {@code text/event-stream} at
 * this point, and reading it here would consume the turn's own chunks.
 */
public class LiteLlmHeaderInterceptor implements Interceptor {
    private static final Logger log = LoggerFactory.getLogger(LiteLlmHeaderInterceptor.class);

    /**
     * The request-body path the correlation id travels in. Public because the sending side
     * ({@code ChatStreamSupport.chatOptions}) has to write the identical path — a mismatch here
     * breaks correlation silently, with every turn simply finding no headers.
     * <p>
     * {@code metadata} is a field LiteLLM already understands, so the id also reaches its spend
     * logs and can be joined on from there.
     */
    public static final String METADATA = "metadata";
    public static final String TURN_ID = "izzy_turn_id";

    static final String CALL_ID_HEADER = "x-litellm-call-id";
    static final String MODEL_NAME_HEADER = "x-litellm-model-name";
    static final String MODEL_API_BASE_HEADER = "x-litellm-model-api-base";
    static final String ATTEMPTED_RETRIES_HEADER = "x-litellm-attempted-retries";
    static final String ATTEMPTED_FALLBACKS_HEADER = "x-litellm-attempted-fallbacks";

    private final LiteLlmHeaderRegistry liteLlmHeaderRegistry;
    private final JsonMapper jsonMapper;

    public LiteLlmHeaderInterceptor(LiteLlmHeaderRegistry liteLlmHeaderRegistry, JsonMapper jsonMapper) {
        this.liteLlmHeaderRegistry = liteLlmHeaderRegistry;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public @NonNull Response intercept(Interceptor.@NonNull Chain chain) throws IOException {
        Request request = chain.request();
        Response response = chain.proceed(request);

        UUID correlationId = correlationId(request);

        if (correlationId == null) {
            return response;
        }

        LiteLlmCallMetadata liteLlmCallMetadata = new LiteLlmCallMetadata(
                header(response, CALL_ID_HEADER),
                header(response, MODEL_NAME_HEADER),
                header(response, MODEL_API_BASE_HEADER),
                intHeader(response, ATTEMPTED_RETRIES_HEADER),
                intHeader(response, ATTEMPTED_FALLBACKS_HEADER));

        if (liteLlmCallMetadata.hasAnyValue()) {
            liteLlmHeaderRegistry.record(correlationId, liteLlmCallMetadata);
        }

        return response;
    }

    /**
     * Nothing here may fail the call. This is bookkeeping on the transport of a turn the user is
     * waiting on, so an unreadable body, or a body that is not ours, costs the turn its proxy
     * accounting and nothing else.
     */
    private @Nullable UUID correlationId(Request request) {
        try {
            RequestBody requestBody = request.body();

            if (requestBody == null || requestBody.isOneShot() || requestBody.isDuplex()) {
                return null;
            }

            Buffer buffer = new Buffer();
            requestBody.writeTo(buffer);

            JsonNode turnIdNode = jsonMapper
                    .readTree(buffer.readString(StandardCharsets.UTF_8))
                    .path(METADATA)
                    .path(TURN_ID);

            if (!turnIdNode.isString()) {
                return null;
            }

            return UUID.fromString(turnIdNode.asString());
        } catch (RuntimeException | IOException exception) {
            log.debug("Could not read a LiteLLM correlation id from the request body.", exception);

            return null;
        }
    }

    private static @Nullable String header(Response response, String name) {
        String value = response.header(name);

        if (value == null || value.isBlank()) {
            return null;
        }

        return value;
    }

    private static @Nullable Integer intHeader(Response response, String name) {
        String value = header(response, name);

        if (value == null) {
            return null;
        }

        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException numberFormatException) {
            return null;
        }
    }
}
