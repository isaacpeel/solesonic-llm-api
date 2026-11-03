package com.solesonic.config.chat;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.springframework.ai.chat.client.advisor.api.MemoryAdvisor.CONVERSATION_ID;

/**
 * Logs each model round exactly as it leaves: after memory, retrieval and message merging have done
 * their work, and beneath {@code ToolCallingAdvisor}, so every tool round is its own request.
 * <p>
 * INFO carries only the shape — message roles and lengths, the tools offered, the tools the model
 * asked for, how much text came back — which is what tells a model ignoring its tools apart from one
 * never offered them. The text itself, and tool arguments, are DEBUG only.
 */
public class ModelExchangeLoggingAdvisor implements StreamAdvisor {
    private static final Logger log = LoggerFactory.getLogger(ModelExchangeLoggingAdvisor.class);

    static final int ORDER = ResponseMetadataCaptureAdvisor.ORDER - 1;

    @Override
    public @NonNull Flux<ChatClientResponse> adviseStream(@NonNull ChatClientRequest chatClientRequest,
                                                          StreamAdvisorChain streamAdvisorChain) {
        Object conversationId = chatClientRequest.context().get(CONVERSATION_ID);
        List<Message> messages = chatClientRequest.prompt().getInstructions();

        log.info("Model request for chat {}: messages={}, tools={}",
                conversationId, shape(messages), toolNames(chatClientRequest));

        if (log.isDebugEnabled()) {
            for (int index = 0; index < messages.size(); index++) {
                Message message = messages.get(index);
                log.debug("Model request for chat {}, message {} ({}):{}{}",
                        conversationId, index, message.getMessageType(), System.lineSeparator(), debugText(message));
            }
        }

        AtomicInteger textLength = new AtomicInteger();
        AtomicReference<String> finishReason = new AtomicReference<>();

        return streamAdvisorChain.nextStream(chatClientRequest)
                .doOnNext(chatClientResponse -> inspect(conversationId, chatClientResponse, textLength, finishReason))
                .doOnComplete(() -> log.info("Model response for chat {}: {} characters of text, finish reason {}",
                        conversationId, textLength.get(), finishReason.get()))
                .doOnError(error -> log.info("Model response for chat {} failed: {}",
                        conversationId, error.getClass().getSimpleName()));
    }

    private static void inspect(Object conversationId,
                                ChatClientResponse chatClientResponse,
                                AtomicInteger textLength,
                                AtomicReference<String> finishReason) {
        ChatResponse chatResponse = chatClientResponse.chatResponse();

        if (chatResponse == null) {
            return;
        }

        for (Generation generation : chatResponse.getResults()) {
            AssistantMessage assistantMessage = generation.getOutput();
            String text = assistantMessage.getText();

            if (text != null) {
                textLength.addAndGet(text.length());
            }

            String generationFinishReason = generation.getMetadata().getFinishReason();

            if (generationFinishReason != null && !generationFinishReason.isBlank()) {
                finishReason.set(generationFinishReason);
            }

            for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                log.info("Model asked for tool {} in chat {}", toolCall.name(), conversationId);
                log.debug("Tool {} arguments in chat {}: {}", toolCall.name(), conversationId, toolCall.arguments());
            }
        }
    }

    private static String shape(List<Message> messages) {
        return messages.stream()
                .map(ModelExchangeLoggingAdvisor::shape)
                .collect(Collectors.joining(", ", "[", "]"));
    }

    /**
     * A tool round's two messages carry nothing in their text: the call lives in the assistant's tool
     * calls and the result in the tool message's responses, so those are what get measured.
     */
    private static String shape(Message message) {
        if (message instanceof ToolResponseMessage toolResponseMessage) {
            String results = toolResponseMessage.getResponses().stream()
                    .map(response -> "%s:%d".formatted(response.name(),
                            response.responseData() == null ? 0 : response.responseData().length()))
                    .collect(Collectors.joining(", "));

            return "TOOL(" + results + ")";
        }

        int textLength = message.getText() == null ? 0 : message.getText().length();

        if (message instanceof AssistantMessage assistantMessage && assistantMessage.hasToolCalls()) {
            String toolCalls = assistantMessage.getToolCalls().stream()
                    .map(AssistantMessage.ToolCall::name)
                    .collect(Collectors.joining(", "));

            return "ASSISTANT:%d calling(%s)".formatted(textLength, toolCalls);
        }

        return "%s:%d".formatted(message.getMessageType(), textLength);
    }

    private static String debugText(Message message) {
        if (message instanceof ToolResponseMessage toolResponseMessage) {
            return toolResponseMessage.getResponses().stream()
                    .map(response -> response.name() + " -> " + response.responseData())
                    .collect(Collectors.joining(System.lineSeparator()));
        }

        if (message instanceof AssistantMessage assistantMessage && assistantMessage.hasToolCalls()) {
            return assistantMessage.getToolCalls().stream()
                    .map(toolCall -> toolCall.name() + "(" + toolCall.arguments() + ")")
                    .collect(Collectors.joining(System.lineSeparator()));
        }

        return message.getText();
    }

    private static List<String> toolNames(ChatClientRequest chatClientRequest) {
        if (!(chatClientRequest.prompt().getOptions() instanceof ToolCallingChatOptions toolCallingChatOptions)) {
            return List.of();
        }

        return toolCallingChatOptions.getToolCallbacks().stream()
                .map(ToolCallback::getToolDefinition)
                .map(ToolDefinition::name)
                .toList();
    }

    @Override
    public @NonNull String getName() {
        return ModelExchangeLoggingAdvisor.class.getSimpleName();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
