package com.solesonic.config.chat;

import com.solesonic.model.chat.SessionTurnRecord;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.solesonic.config.chat.SessionEventCorrelationAdvisor.SESSION_TURN_RECORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionEventCorrelationAdvisorTest {

    private final SessionEventCorrelationAdvisor advisor = new SessionEventCorrelationAdvisor(1001);

    private static ChatClientRequest request(List<Message> messages, Map<String, Object> context) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(messages))
                .context(context)
                .build();
    }

    private static Map<String, Object> context(SessionTurnRecord sessionTurnRecord, String eventId) {
        Map<String, Object> context = new HashMap<>();

        if (sessionTurnRecord != null) {
            context.put(SESSION_TURN_RECORD, sessionTurnRecord);
        }

        if (eventId != null) {
            context.put(SessionMemoryAdvisor.REQUEST_EVENT_ID_CONTEXT_KEY, eventId);
        }

        return context;
    }

    @Test
    void recordsTheEventIdOfTheFirstRoundsUserMessage() {
        SessionTurnRecord sessionTurnRecord = new SessionTurnRecord();
        CallAdvisorChain callAdvisorChain = mock(CallAdvisorChain.class);
        ChatClientRequest request = request(List.of(new UserMessage("hello")), context(sessionTurnRecord, "event-1"));
        ChatClientResponse response = ChatClientResponse.builder().build();
        when(callAdvisorChain.nextCall(request)).thenReturn(response);

        assertThat(advisor.adviseCall(request, callAdvisorChain)).isSameAs(response);

        assertThat(sessionTurnRecord.userEventId()).contains("event-1");
        verify(callAdvisorChain).nextCall(request);
    }

    /**
     * From the second round on the request ends with the round's tool results, and the id the memory
     * advisor names is theirs.
     */
    @Test
    @SuppressWarnings("all")
    void ignoresTheToolResultIdsOfLaterRounds() {
        SessionTurnRecord sessionTurnRecord = new SessionTurnRecord();
        StreamAdvisorChain streamAdvisorChain = mock(StreamAdvisorChain.class);
        when(streamAdvisorChain.nextStream(any())).thenReturn(Flux.empty());

        advisor.adviseStream(request(List.of(new UserMessage("hello")), context(sessionTurnRecord, "event-1")), streamAdvisorChain);

        List<Message> secondRound = List.of(
                new UserMessage("hello"),
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "web_search", "{}")))
                        .build(),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "web_search", "results")))
                        .build());
        advisor.adviseStream(request(secondRound, context(sessionTurnRecord, "event-2")), streamAdvisorChain);

        assertThat(sessionTurnRecord.userEventId()).contains("event-1");
    }

    @Test
    void keepsOnlyTheFirstIdItIsGiven() {
        SessionTurnRecord sessionTurnRecord = new SessionTurnRecord();

        SessionEventCorrelationAdvisor.record(request(List.of(new UserMessage("hello")), context(sessionTurnRecord, "event-1")));
        SessionEventCorrelationAdvisor.record(request(List.of(new UserMessage("hello")), context(sessionTurnRecord, "event-2")));

        assertThat(sessionTurnRecord.userEventId()).contains("event-1");
    }

    /**
     * A message the memory advisor's filter kept out of the session has no event, and the user row
     * stays unlinked for the next turn to copy.
     */
    @Test
    void recordsNothingWhenTheMemoryAdvisorStoredNothing() {
        SessionTurnRecord sessionTurnRecord = new SessionTurnRecord();

        SessionEventCorrelationAdvisor.record(request(List.of(new UserMessage("hello")), context(sessionTurnRecord, null)));

        assertThat(sessionTurnRecord.userEventId()).isEmpty();
    }

    @Test
    void passesARequestWithoutARecordThroughUntouched() {
        CallAdvisorChain callAdvisorChain = mock(CallAdvisorChain.class);
        ChatClientRequest request = request(List.of(new UserMessage("hello")), context(null, "event-1"));

        advisor.adviseCall(request, callAdvisorChain);

        verify(callAdvisorChain).nextCall(request);
    }

    @Test
    void sitsWhereItIsPut() {
        assertThat(advisor.getOrder()).isEqualTo(1001);
        assertThat(advisor.getName()).isEqualTo("SessionEventCorrelationAdvisor");
    }
}
