package com.solesonic.service.chat.events;

import com.solesonic.model.chat.ToolActivityEvent;
import com.solesonic.model.chat.ToolCallSummary;
import com.solesonic.service.chat.TurnToolCalls;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.model.ToolContext;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.solesonic.service.chat.events.ToolActivityReporter.TOOL_ACTIVITY;
import static com.solesonic.service.prompt.PromptService.CHAT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ToolActivityReporterTest {

    @Mock
    private SideChannelPublisher sideChannelPublisher;

    private ToolActivityReporter reporter;
    private TurnToolCalls turnToolCalls;
    private UUID chatId;

    @BeforeEach
    void setUp() {
        turnToolCalls = new TurnToolCalls();
        reporter = new ToolActivityReporter(sideChannelPublisher, turnToolCalls);
        chatId = UUID.randomUUID();
        when(sideChannelPublisher.publish(any(), any(), any())).thenReturn(Mono.just(1L));
    }

    private List<ToolActivityEvent> published() {
        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(sideChannelPublisher, times(2)).publish(eq(chatId), eq(TOOL_ACTIVITY), events.capture());

        return events.getAllValues().stream().map(ToolActivityEvent.class::cast).toList();
    }

    @Test
    void reportsStartThenOkEndUnderOneId() {
        String result = reporter.report("web_search", "{\"query\": \"denver ai\"}", new ToolContext(Map.of(CHAT_ID, chatId)), () -> "hits");

        assertThat(result).isEqualTo("hits");

        List<ToolActivityEvent> events = published();
        assertThat(events).extracting(ToolActivityEvent::phase).containsExactly(ToolActivityEvent.START, ToolActivityEvent.END);
        assertThat(events.get(0).toolCallId()).isEqualTo(events.get(1).toolCallId());
        assertThat(events.get(0).name()).isEqualTo("web_search");
        assertThat(events.get(0).arguments()).isEqualTo("{\"query\": \"denver ai\"}");
        assertThat(events.get(1).status()).isEqualTo(ToolActivityEvent.OK);
        assertThat(events.get(1).durationMs()).isNotNull().isGreaterThanOrEqualTo(0L);

        List<ToolCallSummary> recorded = turnToolCalls.take(chatId);
        assertThat(recorded).singleElement().satisfies(toolCall -> {
            assertThat(toolCall.toolCallId()).isEqualTo(events.get(0).toolCallId());
            assertThat(toolCall.name()).isEqualTo("web_search");
            assertThat(toolCall.status()).isEqualTo(ToolActivityEvent.OK);
        });
        assertThat(turnToolCalls.take(chatId)).isEmpty();
    }

    @Test
    void reportsAFailedCallAndRethrows() {
        IllegalStateException failure = new IllegalStateException("boom");

        assertThatThrownBy(() -> reporter.report("jira_search", "{}", new ToolContext(Map.of(CHAT_ID, chatId.toString())), () -> {
            throw failure;
        })).isSameAs(failure);

        assertThat(published().get(1).status()).isEqualTo(ToolActivityEvent.ERROR);
        assertThat(turnToolCalls.take(chatId)).extracting(ToolCallSummary::status).containsExactly(ToolActivityEvent.ERROR);
    }

    @Test
    void runsUnreportedWithoutAChatId() {
        assertThat(reporter.report("web_search", "{}", new ToolContext(Map.of()), () -> "hits")).isEqualTo("hits");
        assertThat(reporter.report("web_search", "{}", null, () -> "hits")).isEqualTo("hits");

        verify(sideChannelPublisher, never()).publish(any(), any(), any());
        assertThat(turnToolCalls.take(chatId)).isEmpty();
    }

    @Test
    void aFailedPublishDoesNotFailTheTool() {
        when(sideChannelPublisher.publish(any(), any(), any())).thenReturn(Mono.error(new IllegalStateException("redis down")));

        assertThat(reporter.report("web_search", "{}", new ToolContext(Map.of(CHAT_ID, chatId)), () -> "hits")).isEqualTo("hits");
    }

    @Test
    void compactsAndShortensArguments() {
        assertThat(ToolActivityReporter.summarize("{\n  \"query\":   \"a\"\n}")).isEqualTo("{ \"query\": \"a\" }");
        assertThat(ToolActivityReporter.summarize("  ")).isNull();

        String summary = ToolActivityReporter.summarize("x".repeat(500));
        assertThat(summary).hasSize(ToolActivityReporter.MAX_ARGUMENTS_LENGTH).endsWith("…");
    }

    @Test
    void ignoresAChatIdThatIsNotAUuid() {
        assertThat(ToolActivityReporter.chatIdOf(new ToolContext(Map.of(CHAT_ID, "not-a-uuid")))).isNull();
    }
}
