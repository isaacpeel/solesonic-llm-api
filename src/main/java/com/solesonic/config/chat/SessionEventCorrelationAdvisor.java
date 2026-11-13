package com.solesonic.config.chat;

import com.solesonic.model.chat.SessionTurnRecord;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Reads the id of the session event that holds the turn's user message, which
 * {@code SessionMemoryAdvisor} puts on the request it forwards under
 * {@link SessionMemoryAdvisor#REQUEST_EVENT_ID_CONTEXT_KEY}, onto the turn's
 * {@link SessionTurnRecord}. That is how the route links the user's {@code chat_message} row to the
 * session once the turn ends.
 * <p>
 * Ordered directly beneath the memory advisor, so it sees that request before the model is called. It
 * reads the context and nothing else, and never fails a call: a request without a record or an id
 * passes through untouched.
 */
public class SessionEventCorrelationAdvisor implements CallAdvisor, StreamAdvisor {

    public static final String SESSION_TURN_RECORD = "sessionTurnRecord";

    private final int order;

    public SessionEventCorrelationAdvisor(int order) {
        this.order = order;
    }

    @Override
    public @NonNull ChatClientResponse adviseCall(@NonNull ChatClientRequest chatClientRequest,
                                                  CallAdvisorChain callAdvisorChain) {
        record(chatClientRequest);

        return callAdvisorChain.nextCall(chatClientRequest);
    }

    @Override
    public @NonNull Flux<ChatClientResponse> adviseStream(@NonNull ChatClientRequest chatClientRequest,
                                                          StreamAdvisorChain streamAdvisorChain) {
        record(chatClientRequest);

        return streamAdvisorChain.nextStream(chatClientRequest);
    }

    /**
     * Only a round whose trailing message is a user message: from the second round on, the id names
     * the round's tool results.
     */
    static void record(ChatClientRequest chatClientRequest) {
        if (!(chatClientRequest.context().get(SESSION_TURN_RECORD) instanceof SessionTurnRecord sessionTurnRecord)) {
            return;
        }

        if (!(chatClientRequest.context().get(SessionMemoryAdvisor.REQUEST_EVENT_ID_CONTEXT_KEY) instanceof String eventId)) {
            return;
        }

        List<Message> instructions = chatClientRequest.prompt().getInstructions();

        if (instructions.isEmpty() || !(instructions.getLast() instanceof UserMessage)) {
            return;
        }

        sessionTurnRecord.recordUserEventId(eventId);
    }

    @Override
    public @NonNull String getName() {
        return SessionEventCorrelationAdvisor.class.getSimpleName();
    }

    @Override
    public int getOrder() {
        return order;
    }
}
