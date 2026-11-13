package com.solesonic.service.prompt;

import com.solesonic.model.chat.ResponseMetadata;
import com.solesonic.model.chat.SessionTurnRecord;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.model.llm.LlmApiKey;
import com.solesonic.model.prompt.LocalToolSlashCommand;
import com.solesonic.service.chat.ChatMessageService;
import com.solesonic.service.chat.ConversationSessionService;
import com.solesonic.service.chat.ConversationTurn;
import com.solesonic.tools.LocalToolRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The tool route calls a chat model of its own — a different client, a different host and a
 * non-streaming call — so its token accounting has to be recorded here rather than by the streaming
 * helper the other two routes share.
 */
@ExtendWith(MockitoExtension.class)
class ToolCallServiceTest {

    private static final String SERVER_REPORTED_MODEL = "qwen3-8b-instruct-q6";

    private static final LocalToolSlashCommand LOCAL_TOOL_COMMAND =
            new LocalToolSlashCommand("create_invoice", "create_invoice", "Create an invoice");

    private static final Map<String, Object> CONTEXT_MAP = Map.of("userToken", "token-abc");

    private static final LlmApiKey LLM_API_KEY = new LlmApiKey("sk-user-0000000001");

    @Mock
    private McpSyncClient mcpClient;
    @Mock
    private SlashCommandService slashCommandService;
    @Mock
    private LocalToolRegistry localToolRegistry;
    @Mock
    private ChatMessageService chatMessageService;
    @Mock
    private ChatClient taskClient;
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;
    @Mock
    private ConversationSessionService conversationSessionService;
    @Mock
    private ConversationTurn conversationTurn;

    private final SessionTurnRecord sessionTurnRecord = new SessionTurnRecord();

    private UUID chatId;
    private ChatMessage inFlightMessage;
    private ToolCallService toolCallService;

    @BeforeEach
    void setUp() {
        chatId = UUID.randomUUID();

        inFlightMessage = new ChatMessage();
        inFlightMessage.setId(UUID.randomUUID());
        inFlightMessage.setChatId(chatId);

        toolCallService = new ToolCallService(mcpClient, slashCommandService, localToolRegistry, chatMessageService,
                conversationSessionService);

        ReflectionTestUtils.setField(toolCallService, "taskPrompt",
                new ClassPathResource("prompts/task-prompt.st"));
    }

    /**
     * {@code .call()} hands back one already-complete response, with both the answer and the final
     * usage on it.
     */
    @SuppressWarnings("all")
    private static ChatResponse toolResponse(String text, int promptTokens, int completionTokens) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text),
                        ChatGenerationMetadata.builder().finishReason("STOP").build())),
                ChatResponseMetadata.builder()
                        .model(SERVER_REPORTED_MODEL)
                        .id("chatcmpl-1")
                        .usage(new DefaultUsage(promptTokens, completionTokens, promptTokens + completionTokens))
                        .build());
    }

    private void toolAnswers(ChatResponse chatResponse) {
        toolCalled();
        when(callResponseSpec.chatResponse()).thenReturn(chatResponse);
    }

    private void toolCalled() {
        when(localToolRegistry.callback("create_invoice")).thenReturn(mock(ToolCallback.class));
        when(slashCommandService.localTaskClient(any(), eq(LLM_API_KEY))).thenReturn(taskClient);
        when(conversationSessionService.prepareTurn(chatId, inFlightMessage, null)).thenReturn(conversationTurn);
        when(conversationTurn.sessionId()).thenReturn(chatId.toString());
        lenient().when(conversationTurn.record()).thenReturn(sessionTurnRecord);
        when(taskClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.advisors(ArgumentMatchers.<Consumer<ChatClient.AdvisorSpec>>any()))
                .thenReturn(requestSpec);
        when(requestSpec.toolContext(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
    }

    private Flux<String> streamLocal() {
        return toolCallService.streamLocal(chatId, "invoice Acme for 200", inFlightMessage, LOCAL_TOOL_COMMAND, CONTEXT_MAP, LLM_API_KEY);
    }

    /**
     * The turn's assistant row is written by its completion, and the accounting attached to it after,
     * found by the turn's start time: in the other order there would be no row to attach to.
     */
    @Test
    void streamLocalCompletesTheTurnBeforeRecordingItsAccounting() {
        toolAnswers(toolResponse("invoice created", 300, 40));

        StepVerifier.create(streamLocal())
                .expectNext("invoice created")
                .verifyComplete();

        InOrder inOrder = inOrder(conversationTurn, chatMessageService);
        inOrder.verify(conversationTurn).complete("invoice created");
        inOrder.verify(chatMessageService).updateResponseMetadata(eq(chatId), any(ZonedDateTime.class), any(), anyList());
        verify(conversationTurn, never()).abandon();
    }

    /**
     * A tool route gets no image prose, so its session is readied with no attachment context, and the
     * call names that session.
     */
    @Test
    void streamLocalReadiesTheSessionWithNoAttachmentContext() {
        toolAnswers(toolResponse("invoice created", 300, 40));

        StepVerifier.create(streamLocal())
                .expectNext("invoice created")
                .verifyComplete();

        verify(conversationSessionService).prepareTurn(chatId, inFlightMessage, null);
        verify(requestSpec, times(2)).advisors(ArgumentMatchers.<Consumer<ChatClient.AdvisorSpec>>any());
    }

    @Test
    void streamLocalAbandonsTheTurnWhenTheCallFails() {
        toolCalled();
        when(callResponseSpec.chatResponse()).thenThrow(new IllegalStateException("the model is down"));

        assertThatThrownBy(this::streamLocal).isInstanceOf(IllegalStateException.class);

        verify(conversationTurn).abandon();
        verify(conversationTurn, never()).complete(any());
    }

    /**
     * A {@code returnDirect} tool ends the loop with no model round after it, which is what decides
     * that the reply row is copied into the session on the next turn rather than linked now.
     */
    @Test
    void streamLocalRecordsALoopThatEndedWithoutAModelRound() {
        toolAnswers(new ChatResponse(List.of(new Generation(new AssistantMessage("invoice created"),
                ChatGenerationMetadata.builder().finishReason(ToolExecutionResult.FINISH_REASON).build()))));

        StepVerifier.create(streamLocal())
                .expectNext("invoice created")
                .verifyComplete();

        assertThat(sessionTurnRecord.endedWithoutModelRound()).isTrue();
        verify(conversationTurn).complete("invoice created");
    }

    @Test
    void streamLocalPersistsWhatTheServerReportedAboutTheTurn() {
        toolAnswers(toolResponse("invoice created", 300, 40));

        StepVerifier.create(streamLocal())
                .expectNext("invoice created")
                .verifyComplete();

        ArgumentCaptor<ResponseMetadata> metadataCaptor = ArgumentCaptor.captor();

        verify(chatMessageService).updateResponseMetadata(eq(chatId), any(ZonedDateTime.class),
                metadataCaptor.capture(), anyList());

        ResponseMetadata responseMetadata = metadataCaptor.getValue();

        assertThat(responseMetadata.model()).isEqualTo(SERVER_REPORTED_MODEL);
        assertThat(responseMetadata.modelCalls()).isEqualTo(1);
        assertThat(responseMetadata.totalTokens()).isEqualTo(340);
        assertThat(responseMetadata.finishReason()).isEqualTo("stop");
    }

    /**
     * No response at all means no model call happened, so there is nothing to attribute and no row
     * worth looking for.
     */
    @Test
    void streamLocalWithNoResponsePersistsNothing() {
        toolAnswers(null);

        StepVerifier.create(streamLocal())
                .verifyComplete();

        verifyNoInteractions(chatMessageService);
        verify(conversationTurn).complete(null);
    }

    /**
     * A blank answer still cost the tokens it cost. Nothing downstream will have a row to attach
     * them to, which {@code updateResponseMetadata} already treats as a silent no-op — but the
     * decision to record belongs to whether the model ran, not to whether it said anything.
     */
    @Test
    void streamLocalWithABlankAnswerStillRecordsTheModelCall() {
        toolAnswers(toolResponse("   ", 300, 40));

        StepVerifier.create(streamLocal())
                .verifyComplete();

        verify(chatMessageService).updateResponseMetadata(eq(chatId), any(ZonedDateTime.class), any(), anyList());
    }

    /**
     * A server that reported no counts leaves nothing to record — the same rule the streaming routes
     * follow, so neither path writes a row of nulls.
     */
    @Test
    void streamLocalWhenNoUsageWasReportedPersistsNothing() {
        toolAnswers(new ChatResponse(
                List.of(new Generation(new AssistantMessage("invoice created"),
                        ChatGenerationMetadata.builder().finishReason("STOP").build())),
                ChatResponseMetadata.builder()
                        .model(SERVER_REPORTED_MODEL)
                        .usage(new DefaultUsage(0, 0, 0))
                        .build()));

        StepVerifier.create(streamLocal())
                .expectNext("invoice created")
                .verifyComplete();

        verifyNoInteractions(chatMessageService);
    }

    /**
     * The write happens before the answer is even wrapped in a flux, so an exception escaping it
     * would discard a tool result the model has already finished producing.
     */
    @Test
    void streamLocalWithAFailedWriteStillReturnsTheAnswer() {
        toolAnswers(toolResponse("invoice created", 300, 40));

        doThrow(new IllegalStateException("the database is down"))
                .when(chatMessageService).updateResponseMetadata(any(), any(), any(), anyList());

        StepVerifier.create(streamLocal())
                .expectNext("invoice created")
                .verifyComplete();
    }
}
