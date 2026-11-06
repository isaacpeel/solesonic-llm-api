package com.solesonic.tools;

import com.solesonic.service.chat.events.ToolActivityReporter;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Reports each call of the tool it wraps to the chat the call belongs to. It goes outermost, so the
 * reported time covers the whole call, identity handling included.
 */
public class ActivityReportingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ToolActivityReporter toolActivityReporter;

    public ActivityReportingToolCallback(ToolCallback delegate, ToolActivityReporter toolActivityReporter) {
        this.delegate = delegate;
        this.toolActivityReporter = toolActivityReporter;
    }

    @Override
    @NonNull
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    @NonNull
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    @NonNull
    public String call(@NonNull String toolInput) {
        return call(toolInput, null);
    }

    @Override
    @NonNull
    public String call(@NonNull String toolInput, @Nullable ToolContext toolContext) {
        return toolActivityReporter.report(delegate.getToolDefinition().name(), toolInput, toolContext,
                () -> delegate.call(toolInput, toolContext));
    }
}
