package com.solesonic.config.chat;

import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.session.compaction.TokenCountTrigger;
import org.springframework.ai.session.compaction.TurnCountTrigger;
import org.springframework.ai.session.compaction.WindowCompactionStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * The model's memory: the session advisors and the two bounds on what they remember. Fails startup on
 * sizes that could not bound anything, rather than on the first turn that compacts.
 */
@Configuration
public class SessionMemoryConfig {

    @Bean
    public ConversationAdvisors conversationAdvisors(SessionService sessionService,
                                                     @Value("${solesonic.llm.session.compaction-trigger-turns}") int compactionTriggerTurns,
                                                     @Value("${solesonic.llm.session.compaction-keep-turns}") int compactionKeepTurns,
                                                     @Value("${solesonic.llm.session.token-budget}") int tokenBudget) {

        if (compactionKeepTurns < 1 || compactionTriggerTurns < 1) {
            throw new IllegalStateException("SESSION_COMPACTION_TRIGGER_TURNS and SESSION_COMPACTION_KEEP_TURNS must be at least 1");
        }

        if (compactionKeepTurns >= compactionTriggerTurns) {
            throw new IllegalStateException("SESSION_COMPACTION_KEEP_TURNS (" + compactionKeepTurns
                    + ") must be less than SESSION_COMPACTION_TRIGGER_TURNS (" + compactionTriggerTurns
                    + "), or every turn would compact");
        }

        if (tokenBudget < 1) {
            throw new IllegalStateException("SESSION_TOKEN_BUDGET must be at least 1");
        }

        SessionCompaction turnWindow = new SessionCompaction(new TurnCountTrigger(compactionTriggerTurns),
                WindowCompactionStrategy.builder().lastTurns(compactionKeepTurns).build());

        SessionCompaction tokenCeiling = new SessionCompaction(TokenCountTrigger.builder().threshold(tokenBudget).build(),
                WindowCompactionStrategy.builder().tokenBudget(tokenBudget).build());

        List<Advisor> sessionAdvisors = SessionAdvisors.builder(sessionService)
                .compaction(turnWindow.trigger(), turnWindow.strategy())
                .asyncCompaction(true)
                .build();

        int memoryAdvisorOrder = sessionAdvisors.stream()
                .filter(SessionMemoryAdvisor.class::isInstance)
                .mapToInt(Advisor::getOrder)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("SessionAdvisors built no SessionMemoryAdvisor"));

        List<Advisor> advisors = new ArrayList<>(sessionAdvisors);
        advisors.add(new SessionEventCorrelationAdvisor(memoryAdvisorOrder + 1));

        return new ConversationAdvisors(advisors, turnWindow, tokenCeiling);
    }
}
