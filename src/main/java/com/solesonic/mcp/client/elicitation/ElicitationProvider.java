package com.solesonic.mcp.client.elicitation;

import com.solesonic.exception.elicitation.ElicitationException;
import com.solesonic.service.chat.ElicitationService;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpElicitation;
import org.springframework.ai.mcp.annotation.context.StructuredElicitResult;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.UUID;

import static com.solesonic.service.prompt.PromptService.CHAT_ID;

@Component
public class ElicitationProvider {
    private static final Logger log = LoggerFactory.getLogger(ElicitationProvider.class);

    private final ElicitationService elicitationService;

    public ElicitationProvider(ElicitationService elicitationService) {
        this.elicitationService = elicitationService;
    }

    public record ElicitationActionResult(McpSchema.ElicitResult.Action action, UUID chatId, UUID elicitationId) {}

    @SuppressWarnings("unused")
    @McpElicitation(clients = { "solesonic"})
    public StructuredElicitResult<ElicitationActionResult> handleElicitationRequest(McpSchema.ElicitRequest request) {
        log.info("Elicitation request received");

        Map<String, Object> requestMetadata = request.meta();

        if (requestMetadata == null || !requestMetadata.containsKey(CHAT_ID)) {
            throw new ElicitationException("Elicitation request metadata is `null`.");
        }

        UUID chatId = UUID.fromString(request.meta().get(CHAT_ID).toString());

        log.info("Starting elicitation for chat {}", chatId);

        ElicitationService.ElicitationHandle handle = elicitationService.prepareElicitation(chatId);

        elicitationService.emitElicitation(chatId, handle.elicitationId(), request);

        return elicitationService.awaitResultAsync(chatId, handle.elicitationId())
                .subscribeOn(Schedulers.boundedElastic())
                .block();
    }
}
