package com.solesonic.service.prompt;

import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.util.Optional;

/**
 * The two pieces every route that streams from a {@code ChatClient} needs identically: the options
 * the request is made with, and the reduction of a response stream to the text a client renders.
 * <p>
 * Shared rather than duplicated because the first of them is load-bearing and easy to lose — see
 * {@link #chatOptions(String)}.
 */
public final class ChatStreamSupport {

    private ChatStreamSupport() {
    }

    /**
     * Asks the server for {@code stream_options.include_usage}, which is what puts the turn's token
     * counts on the stream's final chunk at all.
     * <p>
     * Pinning it rather than relying on the default is deliberate. Spring AI only defaults it to true
     * while no stream options are set: the moment anything sets one,
     * {@code OpenAiChatModel.createRequest} reads {@code includeUsage} out of it and a null there
     * becomes {@code false}. Setting any unrelated stream option elsewhere would otherwise silently
     * take the token counts away again.
     */
    public static OpenAiChatOptions.Builder chatOptions(String model) {
        return OpenAiChatOptions.builder()
                .model(model)
                .streamUsage(true);
    }

    /**
     * Equivalent to {@code StreamResponseSpec.content()}.
     */
    public static Flux<String> contentFlux(Flux<ChatResponse> chatResponseFlux) {
        return chatResponseFlux
                .map(chatResponse -> Optional.ofNullable(chatResponse.getResult())
                        .map(Generation::getOutput)
                        .map(AbstractMessage::getText)
                        .orElse(""))
                .filter(StringUtils::isNotEmpty);
    }
}
