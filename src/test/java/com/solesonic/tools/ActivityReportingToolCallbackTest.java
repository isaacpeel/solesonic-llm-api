package com.solesonic.tools;

import com.solesonic.service.chat.events.ToolActivityReporter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivityReportingToolCallbackTest {

    @Test
    @SuppressWarnings("unchecked")
    void reportsTheCallUnderTheToolsNameAndReturnsItsResult() {
        ToolCallback delegate = mock(ToolCallback.class);
        ToolActivityReporter reporter = mock(ToolActivityReporter.class);
        ToolDefinition definition = ToolDefinition.builder().name("web_search").description("d").inputSchema("{}").build();
        ToolMetadata metadata = ToolMetadata.builder().returnDirect(true).build();
        ToolContext context = new ToolContext(Map.of("chatId", "c"));

        when(delegate.getToolDefinition()).thenReturn(definition);
        when(delegate.getToolMetadata()).thenReturn(metadata);
        when(delegate.call("{}", context)).thenReturn("hits");
        when(reporter.report(eq("web_search"), eq("{}"), eq(context), any())).thenAnswer(invocation ->
                ((Supplier<String>) invocation.getArgument(3)).get());

        ActivityReportingToolCallback callback = new ActivityReportingToolCallback(delegate, reporter);

        assertThat(callback.call("{}", context)).isEqualTo("hits");
        assertThat(callback.getToolDefinition()).isSameAs(definition);
        assertThat(callback.getToolMetadata()).isSameAs(metadata);
        verify(delegate).call("{}", context);
    }
}
