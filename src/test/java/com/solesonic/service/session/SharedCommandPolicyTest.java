package com.solesonic.service.session;

import com.solesonic.config.session.SharedSessionSettings;
import com.solesonic.exception.session.SessionException;
import com.solesonic.model.prompt.AgentSlashCommand;
import com.solesonic.model.prompt.LocalToolSlashCommand;
import com.solesonic.model.prompt.ModelSlashCommand;
import com.solesonic.model.prompt.PromptSlashCommand;
import com.solesonic.model.prompt.SlashCommand;
import com.solesonic.model.prompt.ToolSlashCommand;
import com.solesonic.model.session.SessionErrorCode;
import com.solesonic.service.prompt.SlashCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SharedCommandPolicyTest {

    private static final SlashCommand MODEL = new ModelSlashCommand();
    private static final SlashCommand WEB_SEARCH = new ToolSlashCommand("web_search", "web_search", "Search", null);
    private static final SlashCommand GMAIL_SEND = new ToolSlashCommand("gmail_send", "gmail_send", "Send mail", null);
    private static final SlashCommand GENERATE_IMAGE = new LocalToolSlashCommand("generate_image", "generate_image", "Draw");
    private static final SlashCommand SUMMARIZE = new PromptSlashCommand("summarize", "summarize", "Summarize");
    private static final SlashCommand AUDIT = new PromptSlashCommand("audit", "audit", "Audit the inbox");
    private static final SlashCommand FEUD = new AgentSlashCommand("feud", "feud", "Family feud");

    private static final List<SlashCommand> CATALOGUE = List.of(MODEL, SUMMARIZE, AUDIT, WEB_SEARCH, GMAIL_SEND, FEUD, GENERATE_IMAGE);

    @Mock
    private SlashCommandService slashCommandService;

    @BeforeEach
    void setUp() {
        when(slashCommandService.slashCommands()).thenReturn(CATALOGUE);
        when(slashCommandService.typeAhead(any())).thenReturn(CATALOGUE);
    }

    private SharedCommandPolicy policy(Set<String> tools, Set<String> commands) {
        SharedSessionSettings settings = new SharedSessionSettings(Set.of("feud"), Duration.ofHours(24), 5, 20, 40,
                Duration.ofSeconds(600), Duration.ofDays(7),
                new SharedSessionSettings.Conversation(true, 8, 50, Duration.ofSeconds(2100), tools, 3,
                        Duration.ofDays(90), 20, commands));

        return new SharedCommandPolicy(slashCommandService, settings);
    }

    private SharedCommandPolicy defaults() {
        return policy(Set.of("web_search", "generate_image"), Set.of("web_search", "generate_image", "model"));
    }

    private static void assertRefusing(SharedCommandPolicy policy, String command) {
        assertThatThrownBy(() -> policy.require(Set.of(command)))
                .isInstanceOfSatisfying(SessionException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(SessionErrorCode.COMMAND_NOT_ALLOWED);
                    assertThat(exception.getMessage()).contains("/" + command);
                });
    }

    @Test
    void aListedToolCommandWhoseToolIsAllowlistedIsAllowed() {
        assertThat(defaults().require(Set.of("web_search"))).containsExactly("web_search");
    }

    @Test
    void aListedLocalToolCommandWhoseToolIsAllowlistedIsAllowed() {
        assertThat(defaults().require(Set.of("generate_image"))).containsExactly("generate_image");
    }

    @Test
    void theModelCommandIsAllowedWhenListed() {
        assertThat(defaults().require(Set.of("model"))).containsExactly("model");
    }

    @Test
    void aToolCommandThatIsNotListedIsRefused() {
        assertRefusing(policy(Set.of("web_search", "gmail_send"), Set.of("web_search")), "gmail_send");
    }

    @Test
    void aListedToolCommandWhoseToolIsNotAllowlistedIsRefused() {
        assertRefusing(policy(Set.of("web_search"), Set.of("web_search", "gmail_send")), "gmail_send");
    }

    @Test
    void anAgentCommandIsRefusedEvenWhenListed() {
        assertRefusing(policy(Set.of("web_search"), Set.of("web_search", "feud")), "feud");
    }

    @Test
    void aPromptCommandIsRefusedUnlessListed() {
        SharedCommandPolicy policy = policy(Set.of("web_search"), Set.of("summarize"));

        assertThat(policy.require(Set.of("summarize"))).containsExactly("summarize");
        assertRefusing(policy, "audit");
    }

    @Test
    void aCommandNotInTheCatalogueIsRefused() {
        assertRefusing(defaults(), "nonsense");
    }

    @Test
    void aNameSharedByAnAllowedAndAnAgentCommandIsRefused() {
        when(slashCommandService.slashCommands()).thenReturn(List.of(WEB_SEARCH, new AgentSlashCommand("web_search", "web_search", "A2A")));

        assertRefusing(defaults(), "web_search");
    }

    @Test
    void theRefusalNamesTheFirstRefusedCommandOfSeveral() {
        assertThatThrownBy(() -> defaults().require(new java.util.LinkedHashSet<>(List.of("web_search", "feud"))))
                .isInstanceOfSatisfying(SessionException.class, exception -> assertThat(exception.getMessage()).contains("/feud"));
    }

    @Test
    void blankEntriesAreDroppedAndNullIsEmpty() {
        assertThat(defaults().require(null)).isEmpty();
        assertThat(defaults().require(Set.of(" ", "web_search "))).containsExactly("web_search");
    }

    @Test
    void theCatalogueIsFilteredToWhatIsAllowed() {
        assertThat(defaults().catalogue(null)).containsExactly(MODEL, WEB_SEARCH, GENERATE_IMAGE);
    }

    @Test
    void theCatalogueFilterAppliesToTheTypeAheadResultToo() {
        when(slashCommandService.typeAhead("a")).thenReturn(List.of(AUDIT, WEB_SEARCH, FEUD));

        assertThat(defaults().catalogue("a")).containsExactly(WEB_SEARCH);
    }

    @Test
    void startupDropsAListedToolCommandWhoseToolIsNotAllowlistedInsteadOfFailing() {
        SharedCommandPolicy policy = policy(Set.of("web_search"), Set.of("web_search", "gmail_send"));

        when(slashCommandService.typeAhead(null)).thenReturn(CATALOGUE);

        assertThatCode(policy::verifyAgainstCatalogue).doesNotThrowAnyException();

        assertThat(policy.droppedCommands()).containsExactly("gmail_send");
        assertThat(policy.catalogue(null)).extracting(SlashCommand::command).doesNotContain("gmail_send");
    }

    @Test
    void startupNeverThrowsEvenForAnUnexpectedFailure() {
        when(slashCommandService.slashCommands()).thenThrow(new IllegalArgumentException("odd"));

        assertThatCode(defaults()::verifyAgainstCatalogue).doesNotThrowAnyException();
    }

    @Test
    void theCatalogueCheckRunsAfterTheStartupWarmup() throws Exception {
        int check = SharedCommandPolicy.class.getMethod("verifyAgainstCatalogue")
                .getAnnotation(org.springframework.core.annotation.Order.class).value();
        int warmup = com.solesonic.service.prompt.SlashCommandService.class.getMethod("warmupCatalogOnStartup")
                .getAnnotation(org.springframework.core.annotation.Order.class).value();

        assertThat(warmup).isLessThan(check);
    }

    @Test
    void startupPassesForTheDefaultsAndForAListedPromptAndIgnoresAnAgentEntry() {
        assertThatCode(defaults()::verifyAgainstCatalogue).doesNotThrowAnyException();
        assertThatCode(policy(Set.of("web_search"), Set.of("summarize", "feud"))::verifyAgainstCatalogue).doesNotThrowAnyException();
    }

    @Test
    void startupDoesNotFailWhenTheCatalogueCannotBeRead() {
        when(slashCommandService.slashCommands()).thenThrow(new IllegalStateException("MCP is down"));

        assertThatCode(defaults()::verifyAgainstCatalogue).doesNotThrowAnyException();
    }
}
