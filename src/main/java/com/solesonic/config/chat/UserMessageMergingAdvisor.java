package com.solesonic.config.chat;

import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Joins every run of adjacent user messages into one, immediately before the model call.
 * <p>
 * Image context is deliberately a user message of its own ahead of the user's words, so the
 * retrieval advisor rewrites only the question. Some chat templates (Mistral, Gemma) reject two
 * user messages in a row outright, so the two are joined here, after memory and retrieval have
 * done their work and before the request leaves. Nothing is persisted from the merged prompt: it sits
 * beneath the session memory advisor, which has already stored the user's own message.
 */
public class UserMessageMergingAdvisor implements CallAdvisor, StreamAdvisor {

    /**
     * Beneath the retrieval advisor (order 0) and chat memory, which both add or rewrite user
     * messages, and above the exchange logging and metadata capture, so what is logged is what
     * leaves.
     */
    static final int ORDER = ModelExchangeLoggingAdvisor.ORDER - 1;

    private static final String SEPARATOR = System.lineSeparator() + System.lineSeparator();

    @Override
    public @NonNull ChatClientResponse adviseCall(@NonNull ChatClientRequest chatClientRequest,
                                                  CallAdvisorChain callAdvisorChain) {
        return callAdvisorChain.nextCall(merged(chatClientRequest));
    }

    @Override
    public @NonNull Flux<ChatClientResponse> adviseStream(@NonNull ChatClientRequest chatClientRequest,
                                                          StreamAdvisorChain streamAdvisorChain) {
        return streamAdvisorChain.nextStream(merged(chatClientRequest));
    }

    static ChatClientRequest merged(ChatClientRequest chatClientRequest) {
        Prompt prompt = chatClientRequest.prompt();
        List<Message> instructions = prompt.getInstructions();
        List<Message> mergedInstructions = mergeAdjacentUserMessages(instructions);

        if (mergedInstructions.size() == instructions.size()) {
            return chatClientRequest;
        }

        Prompt mergedPrompt = prompt.mutate()
                .messages(mergedInstructions)
                .build();

        return chatClientRequest.mutate()
                .prompt(mergedPrompt)
                .build();
    }

    static List<Message> mergeAdjacentUserMessages(List<Message> messages) {
        List<Message> mergedMessages = new ArrayList<>(messages.size());

        for (Message message : messages) {
            int lastIndex = mergedMessages.size() - 1;

            if (message instanceof UserMessage userMessage
                    && lastIndex >= 0
                    && mergedMessages.get(lastIndex) instanceof UserMessage previousUserMessage) {
                mergedMessages.set(lastIndex, join(previousUserMessage, userMessage));
            } else {
                mergedMessages.add(message);
            }
        }

        return mergedMessages;
    }

    private static UserMessage join(UserMessage first, UserMessage second) {
        String firstText = first.getText();
        String secondText = second.getText();

        String text;

        if (firstText == null || firstText.isBlank()) {
            text = secondText == null ? "" : secondText;
        } else if (secondText == null || secondText.isBlank()) {
            text = firstText;
        } else {
            text = firstText + SEPARATOR + secondText;
        }

        List<Media> media = new ArrayList<>(first.getMedia());
        media.addAll(second.getMedia());

        Map<String, Object> metadata = new HashMap<>(first.getMetadata());
        metadata.putAll(second.getMetadata());

        return UserMessage.builder()
                .text(text)
                .media(media)
                .metadata(metadata)
                .build();
    }

    @Override
    public @NonNull String getName() {
        return UserMessageMergingAdvisor.class.getSimpleName();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
