package com.solesonic.mcp.client.elicitation;

import com.solesonic.exception.elicitation.ElicitationException;
import com.solesonic.service.chat.ElicitationService;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpElicitation;
import org.springaicommunity.mcp.context.StructuredElicitResult;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.MapType;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.solesonic.service.ollama.PromptService.CHAT_ID;

@Component
public class ElicitationProvider {
    private static final Logger log = LoggerFactory.getLogger(ElicitationProvider.class);

    private final ElicitationService elicitationService;
    private final JsonMapper jsonMapper;

    public ElicitationProvider(ElicitationService elicitationService,
                               JsonMapper jsonMapper) {
        this.elicitationService = elicitationService;
        this.jsonMapper = jsonMapper;
    }

    public record DeleteConfirmation(boolean confirmed, String chatId) {}

    @SuppressWarnings("unused")
    @McpElicitation(clients = {"solesonic","mcp-client - solesonic"})
    public StructuredElicitResult<DeleteConfirmation> handleElicitationRequest(McpSchema.ElicitRequest request) {
        log.info("Elicitation request received");

        Map<String, Object> requestMetadata = request.meta();

        if(requestMetadata == null || !requestMetadata.containsKey(CHAT_ID)) {
            throw new ElicitationException("Elicitation request metadata is `null`.");
        }

        UUID chatId = UUID.fromString(request.meta().get(CHAT_ID).toString());

        MapType mapType = jsonMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class);

        String json = jsonMapper.writeValueAsString(request);
        Map<String, Object> requestMap = jsonMapper.readerFor(mapType).readValue(json);

        String name = Optional.ofNullable(requestMap.get("name"))
                .map(Object::toString)
                .orElse("elicitation");


        log.info("Starting elicitation: {} for chat {}", name, chatId);

        ElicitationService.ElicitationHandle handle = elicitationService.prepareElicitation(chatId, name);

        elicitationService.emitElicitation(chatId, handle.elicitationId(), request);

        return elicitationService.awaitResultAsync(chatId, handle.elicitationId())
                .subscribeOn(Schedulers.boundedElastic())
                .block();
    }
}
