package com.solesonic.config.chat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.session.advisor.SessionTurnAdvisor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bounds on the model's memory are required and must be able to bound something, or startup fails
 * rather than the first turn that compacts.
 */
class SessionMemoryConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SessionMemoryConfig.class)
            .withBean(SessionService.class, () -> DefaultSessionService.builder()
                    .sessionRepository(InMemorySessionRepository.builder().build())
                    .build());

    private ApplicationContextRunner withSizes(String triggerTurns, String keepTurns, String tokenBudget) {
        return contextRunner.withPropertyValues(
                "solesonic.llm.session.compaction-trigger-turns=" + triggerTurns,
                "solesonic.llm.session.compaction-keep-turns=" + keepTurns,
                "solesonic.llm.session.token-budget=" + tokenBudget);
    }

    @Test
    void buildsTheSessionAdvisorsAndTheCorrelationAdvisorDirectlyBeneathTheMemoryAdvisor() {
        withSizes("20", "10", "16000").run(context -> {
            ConversationAdvisors conversationAdvisors = context.getBean(ConversationAdvisors.class);

            assertThat(conversationAdvisors.advisors()).hasSize(3);
            assertThat(conversationAdvisors.advisors().get(0)).isInstanceOf(SessionTurnAdvisor.class);
            assertThat(conversationAdvisors.advisors().get(1)).isInstanceOf(SessionMemoryAdvisor.class);
            assertThat(conversationAdvisors.advisors().get(2)).isInstanceOf(SessionEventCorrelationAdvisor.class);
            assertThat(conversationAdvisors.advisors().get(2).getOrder())
                    .isEqualTo(conversationAdvisors.advisors().get(1).getOrder() + 1);
        });
    }

    @Test
    void startupFailsWhenASizeIsMissing() {
        contextRunner.withPropertyValues(
                        "solesonic.llm.session.compaction-trigger-turns=20",
                        "solesonic.llm.session.compaction-keep-turns=10")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void startupFailsForAZeroTurnSize() {
        withSizes("20", "0", "16000").run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("at least 1"));
    }

    @Test
    void startupFailsWhenTheWindowIsNotSmallerThanTheTrigger() {
        withSizes("10", "10", "16000").run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("must be less than"));
    }

    @Test
    void startupFailsForAZeroTokenBudget() {
        withSizes("20", "10", "0").run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("SESSION_TOKEN_BUDGET"));
    }
}
