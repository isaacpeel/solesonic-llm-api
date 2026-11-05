package com.solesonic.config.session;

import com.solesonic.model.session.SessionMode;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SharedSessionConfigTest {

    private static final Duration CHAT_TIMEOUT = Duration.ofMinutes(30);
    private static final String COMMANDS = "model";

    private final SharedSessionConfig config = new SharedSessionConfig();

    @Test
    void buildsSettingsWithPerModeRunLocks() {
        SharedSessionSettings settings = settings(2100, 8, "web_search, generate_image ,web_search");

        assertThat(settings.runLock(SessionMode.AGENT)).isEqualTo(Duration.ofSeconds(600));
        assertThat(settings.runLock(SessionMode.CONVERSATION)).isEqualTo(Duration.ofSeconds(2100));
        assertThat(settings.runLock()).isEqualTo(Duration.ofSeconds(600));
        assertThat(settings.conversation().enabled()).isTrue();
        assertThat(settings.conversation().maxParticipants()).isEqualTo(8);
        assertThat(settings.conversation().maxMessageCharacters()).isEqualTo(8000);
        assertThat(settings.conversation().forkMaxMessages()).isEqualTo(500);
        assertThat(settings.conversation().tools()).containsExactlyInAnyOrder("web_search", "generate_image");
    }

    @Test
    void rejectsAConversationRunLockThatDoesNotOutlastTheChatTimeout() {
        assertThatThrownBy(() -> settings(1800, 8, "web_search"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHARED_CONVERSATION_RUN_LOCK_SECONDS");
        assertThatThrownBy(() -> settings(600, 8, "web_search"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsTooFewParticipantsAndNonPositiveLimits() {
        assertThatThrownBy(() -> settings(2100, 1, "web_search")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.sharedSessionSettings("feud", 24, 5, 500, 40, 600, 7,
                300, CHAT_TIMEOUT, true, 8, 0, 2100, "web_search", 500, 90, 20, COMMANDS))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.sharedSessionSettings("feud", 24, 5, 500, 40, 600, 7,
                300, CHAT_TIMEOUT, true, 8, 8000, 2100, "web_search", 0, 90, 20, COMMANDS))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stillRejectsAnAgentRunLockThatDoesNotOutlastTheA2aTimeout() {
        assertThatThrownBy(() -> config.sharedSessionSettings("feud", 24, 5, 500, 40, 300, 7,
                300, CHAT_TIMEOUT, true, 8, 8000, 2100, "web_search", 500, 90, 20, COMMANDS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHARED_SESSION_RUN_LOCK_SECONDS");
    }

    @Test
    void aConversationIdlesLongerThanAnAgentSessionAndCapsActiveSessions() {
        SharedSessionSettings settings = settings(2100, 8, "web_search");

        assertThat(settings.idle()).isEqualTo(Duration.ofDays(7));
        assertThat(settings.conversation().idle()).isEqualTo(Duration.ofDays(90));
        assertThat(settings.conversation().maxActiveSessions()).isEqualTo(20);
    }

    @Test
    void rejectsAConversationIdleWindowOrSessionCapBelowOne() {
        assertThatThrownBy(() -> config.sharedSessionSettings("feud", 24, 5, 500, 40, 600, 7,
                300, CHAT_TIMEOUT, true, 8, 8000, 2100, "web_search", 500, 0, 20, COMMANDS))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SHARED_CONVERSATION_IDLE_DAYS");
        assertThatThrownBy(() -> config.sharedSessionSettings("feud", 24, 5, 500, 40, 600, 7,
                300, CHAT_TIMEOUT, true, 8, 8000, 2100, "web_search", 500, 90, 0, COMMANDS))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SHARED_CONVERSATION_MAX_ACTIVE_SESSIONS");
    }

    @Test
    void namesEveryToolOutsideTheWebSearchSetAndImageGeneration() {
        assertThat(SharedSessionConfig.unexpectedTools(java.util.Set.of("web_search", "generate_image", "gmail_send", "jira_search")))
                .containsExactlyInAnyOrder("gmail_send", "jira_search");
        assertThat(SharedSessionConfig.unexpectedTools(java.util.Set.of("web_search_news", "web_extract_content"))).isEmpty();
    }

    @Test
    void anEmptyToolListIsAllowed() {
        assertThat(settings(2100, 8, " ").conversation().tools()).isEmpty();
    }

    @Test
    void parsesTheCommandAllowlist() {
        SharedSessionSettings settings = config.sharedSessionSettings("feud", 24, 5, 500, 40, 600, 7,
                300, CHAT_TIMEOUT, true, 8, 8000, 2100, "web_search, generate_image", 500, 90, 20, " web_search, model ,web_search ");

        assertThat(settings.conversation().commands()).containsExactlyInAnyOrder("web_search", "model");
    }

    @Test
    void theDefaultCommandsAreTheWebSearchToolsImageGenerationAndModel() {
        assertThat(SharedSessionSettings.Conversation.defaults().commands()).containsExactlyInAnyOrder(
                "web_search", "web_search_advanced", "web_search_news", "web_extract_content", "generate_image", "model");
    }

    @Test
    void aCommandForAToolThatIsNotAllowlistedIsDroppedAndDoesNotFailStartup() {
        SharedSessionSettings settings = config.sharedSessionSettings("feud", 24, 5, 500, 40, 600, 7,
                300, CHAT_TIMEOUT, true, 8, 8000, 2100, "web_search", 500, 90, 20, "web_search,generate_image,model");

        assertThat(settings.conversation().commands()).containsExactlyInAnyOrder("web_search", "model");
    }

    @Test
    void anEmptyCommandListIsAllowed() {
        assertThat(config.sharedSessionSettings("feud", 24, 5, 500, 40, 600, 7,
                300, CHAT_TIMEOUT, true, 8, 8000, 2100, "web_search", 500, 90, 20, " ").conversation().commands()).isEmpty();
    }

    private SharedSessionSettings settings(long conversationLockSeconds, int maxParticipants, String tools) {
        return config.sharedSessionSettings("feud", 24, 5, 500, 40, 600, 7,
                300, CHAT_TIMEOUT, true, maxParticipants, 8000, conversationLockSeconds, tools, 500, 90, 20, COMMANDS);
    }
}
