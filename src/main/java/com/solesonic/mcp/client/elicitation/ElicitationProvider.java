package com.solesonic.mcp.client.elicitation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.solesonic.exception.elicitation.ElicitationException;
import com.solesonic.service.chat.ElicitationService;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpElicitation;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.solesonic.service.ollama.PromptService.CHAT_ID;

@Component
public class ElicitationProvider {
    private static final Logger log = LoggerFactory.getLogger(ElicitationProvider.class);

    private final ElicitationService elicitationService;
    private final ObjectMapper objectMapper;

    public ElicitationProvider(ElicitationService elicitationService,
                               ObjectMapper objectMapper) {
        this.elicitationService = elicitationService;
        this.objectMapper = objectMapper;
    }

    @SuppressWarnings("unused")
    @McpElicitation(clients = {"solesonic","mcp-client - solesonic"})
    public McpSchema.ElicitResult handleElicitationRequest(McpSchema.ElicitRequest request) {
        log.info("Elicitation request received");

        Map<String, Object> requestMetadata = request.meta();

        if(requestMetadata == null || !requestMetadata.containsKey(CHAT_ID)) {
            throw new ElicitationException("Elicitation request medadata is `null`.");
        }

        UUID chatId = UUID.fromString(request.meta().get(CHAT_ID).toString());

        @SuppressWarnings("unchecked")
        Map<String, Object> requestMap = objectMapper.convertValue(request, Map.class);

        String name = Optional.ofNullable(requestMap.get("name"))
                .map(Object::toString)
                .orElse("elicitation");

        
        log.info("Starting elicitation: {} for chat {}", name, chatId);
        
        ElicitationService.ElicitationHandle handle = elicitationService.prepareElicitation(chatId, name);

        elicitationService.emitElicitation(chatId, handle.getElicitationId(), request);

        return elicitationService.awaitResultAsync(chatId, handle.getElicitationId())
                .subscribeOn(Schedulers.boundedElastic())
                .block();
    }
}
