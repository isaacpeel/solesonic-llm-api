package com.solesonic.model.a2a;

import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The A2A extension through which an agent asks the user a structured question and receives the
 * answer. The client names it on every message; the agent raises a prompt only when it sees it.
 */
public final class A2AElicitationExtension {
    private static final Logger log = LoggerFactory.getLogger(A2AElicitationExtension.class);

    public static final String URI = "urn:solesonic:a2a:extension:elicitation:v1";
    static final String ELICITATION = "elicitation";
    static final String ELICITATION_RESPONSE = "elicitationResponse";
    static final int MAX_PROMPT_BYTES = 16_384;

    private static final String MODE = "mode";
    private static final String FORM_MODE = "form";
    private static final String MESSAGE = "message";
    private static final String REQUESTED_SCHEMA = "requestedSchema";
    private static final String META = "_meta";
    private static final String TYPE = "type";
    private static final String OBJECT_TYPE = "object";
    private static final String PROPERTIES = "properties";
    private static final String ACTION = "action";
    private static final String ACCEPT = "accept";
    private static final String CONTENT = "content";

    private A2AElicitationExtension() {
    }

    /**
     * The prompt an {@code INPUT_REQUIRED} status carries: the first {@link DataPart} holding an
     * {@code elicitation} object. A prompt that cannot be asked as a form is empty, which leaves the
     * turn to end as it would without one, so the user can still type an answer.
     */
    public static Optional<A2APrompt> prompt(Message statusMessage, JsonMapper jsonMapper) {

        for (Part<?> part : statusMessage.parts()) {
            if (part instanceof DataPart dataPart
                    && dataPart.data() instanceof Map<?, ?> data
                    && data.containsKey(ELICITATION)) {
                return readPrompt(data.get(ELICITATION), jsonMapper);
            }
        }

        return Optional.empty();
    }

    private static Optional<A2APrompt> readPrompt(Object elicitationData, JsonMapper jsonMapper) {
        if (!(elicitationData instanceof Map<?, ?>)) {
            log.warn("Ignoring an A2A prompt that is not an object");
            return Optional.empty();
        }

        try {
            if (jsonMapper.writeValueAsBytes(elicitationData).length > MAX_PROMPT_BYTES) {
                log.warn("Ignoring an A2A prompt over {} bytes", MAX_PROMPT_BYTES);
                return Optional.empty();
            }

            Map<String, Object> elicitation = jsonMapper.convertValue(elicitationData, new TypeReference<LinkedHashMap<String, Object>>() {
            });

            if (!FORM_MODE.equals(elicitation.get(MODE))) {
                log.warn("Ignoring an A2A prompt that is not a form");
                return Optional.empty();
            }

            if (!(elicitation.get(MESSAGE) instanceof String message) || StringUtils.isBlank(message)) {
                log.warn("Ignoring an A2A prompt with no message");
                return Optional.empty();
            }

            if (!(elicitation.get(REQUESTED_SCHEMA) instanceof Map<?, ?> schema)
                    || !OBJECT_TYPE.equals(schema.get(TYPE))
                    || !(schema.get(PROPERTIES) instanceof Map<?, ?> properties)
                    || properties.isEmpty()) {
                log.warn("Ignoring an A2A prompt whose schema is not an object of properties");
                return Optional.empty();
            }

            Map<String, Object> requestedSchema = jsonMapper.convertValue(schema, new TypeReference<LinkedHashMap<String, Object>>() {
            });

            Map<String, Object> meta = null;

            if (elicitation.get(META) instanceof Map<?, ?> metaData) {
                meta = jsonMapper.convertValue(metaData, new TypeReference<LinkedHashMap<String, Object>>() {
                });
            }

            return Optional.of(new A2APrompt(message, requestedSchema, meta));
        } catch (JacksonException | IllegalArgumentException exception) {
            log.warn("Ignoring an unreadable A2A prompt: {}", exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * The accepted answer, sent as the next message of the same task: the extension named again, and
     * one {@link DataPart} carrying only the narrowed content.
     */
    public static Message answer(UUID chatId, String taskId, Map<String, Object> content) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put(ACTION, ACCEPT);
        response.put(CONTENT, content);

        return Message.builder()
                .role(Message.Role.ROLE_USER)
                .contextId(chatId.toString())
                .taskId(taskId)
                .messageId(UUID.randomUUID().toString())
                .extensions(List.of(URI))
                .parts(new DataPart(Map.of(ELICITATION_RESPONSE, response)))
                .build();
    }
}
