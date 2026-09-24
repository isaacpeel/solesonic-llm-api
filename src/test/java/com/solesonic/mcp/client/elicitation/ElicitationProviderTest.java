package com.solesonic.mcp.client.elicitation;

import com.solesonic.exception.elicitation.ElicitationException;
import com.solesonic.service.chat.events.ElicitationService;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ElicitationProviderTest {
    private static final UUID CHAT_ID = UUID.randomUUID();
    private static final UUID ELICITATION_ID = UUID.randomUUID();

    @Mock
    private ElicitationService elicitationService;

    private ElicitationProvider elicitationProvider;

    @BeforeEach
    void setUp() {
        elicitationProvider = new ElicitationProvider(elicitationService);
    }

    /**
     * The tool must receive the user's answers as {@code content} and nothing of ours: the result
     * this returns is what reaches the MCP server, so bookkeeping in {@code _meta} would leak to it.
     */
    @Test
    void returnsTheAnswerAsContentWithNoMeta() {
        McpSchema.ElicitFormRequest request = new McpSchema.ElicitFormRequest("Who?",
                Map.of("type", "object"), Map.of("chatId", CHAT_ID.toString()));

        when(elicitationService.prepareElicitation(CHAT_ID))
                .thenReturn(new ElicitationService.ElicitationHandle(ELICITATION_ID));
        when(elicitationService.awaitResultAsync(CHAT_ID, ELICITATION_ID))
                .thenReturn(Mono.just(new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT,
                        Map.of("assigneeAccountId", "account-1"))));

        McpSchema.ElicitResult elicitResult = elicitationProvider.handleElicitationRequest(request);

        assertThat(elicitResult.action()).isEqualTo(McpSchema.ElicitResult.Action.ACCEPT);
        assertThat(elicitResult.content()).isEqualTo(Map.of("assigneeAccountId", "account-1"));
        assertThat(elicitResult.meta()).isNull();

        verify(elicitationService).emitElicitation(CHAT_ID, ELICITATION_ID, request);
    }

    @Test
    void refusesARequestThatNamesNoChat() {
        McpSchema.ElicitFormRequest request = new McpSchema.ElicitFormRequest("Who?", Map.of("type", "object"), null);

        assertThatThrownBy(() -> elicitationProvider.handleElicitationRequest(request))
                .isInstanceOf(ElicitationException.class);

        verify(elicitationService, never()).emitElicitation(any(), any(), any());
    }
}
