package com.solesonic.tools;

import com.solesonic.model.prompt.SlashCommand;
import com.solesonic.service.xero.XeroInvoiceService;
import com.solesonic.tools.xero.CreateXeroInvoiceTools;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * That a {@link LocalTool} bean becomes a slash command with no registration step of its own.
 * <p>
 * This is the whole of AC #2, and it is worth pinning because the wiring is invisible: a tool is
 * discovered through the injected {@code List<LocalTool>}, so forgetting the marker interface
 * produces a class that still compiles, still carries a working {@code @Tool} method, and simply
 * never appears in a chat.
 */
class LocalToolRegistryTest {

    private static final String CREATE_XERO_INVOICE = "create_xero_invoice";

    private LocalToolRegistry registry() {
        return new LocalToolRegistry(List.of(new CreateXeroInvoiceTools(mock(XeroInvoiceService.class))));
    }

    @Test
    void exposesTheXeroInvoiceToolAsASlashCommand() {
        List<SlashCommand> slashCommands = registry().asSlashCommands();

        assertThat(slashCommands)
                .extracting(SlashCommand::command)
                .contains(CREATE_XERO_INVOICE);
    }

    /**
     * The command and the tool name are the same string, because {@code ToolCallService.streamLocal}
     * looks the callback up by the command the user typed.
     */
    @Test
    void resolvesTheCallbackUnderTheCommandTheUserTypes() {
        LocalToolRegistry localToolRegistry = registry();

        assertThat(localToolRegistry.callback(CREATE_XERO_INVOICE)).isNotNull();
        assertThat(localToolRegistry.callback(CREATE_XERO_INVOICE).getToolDefinition().name())
                .isEqualTo(CREATE_XERO_INVOICE);
    }

    /**
     * A description is what the model routes on and what a user sees in the command list; an empty
     * one makes the command undiscoverable in practice.
     */
    @Test
    void carriesADescriptionForEveryCommand() {
        assertThat(registry().asSlashCommands())
                .allSatisfy(slashCommand -> assertThat(slashCommand.description()).isNotBlank());
    }
}
