package com.solesonic.config.chat;

import org.springframework.ai.chat.client.advisor.api.Advisor;

import java.util.List;

/**
 * The session advisors every conversation route's {@code ChatClient} carries, with the two bounds
 * on what they remember.
 * <p>
 * A wrapper rather than a bare {@code List<Advisor>}, so Spring never collects the list with other
 * {@code Advisor} beans. There is exactly one: {@code SessionTurnAdvisor} de-duplicates its
 * asynchronous compactions per instance, so a second instance could compact one session twice at
 * once. The turn window compacts after every call and before every turn; the token ceiling only before
 * every turn, after the turn window.
 */
public record ConversationAdvisors(List<Advisor> advisors,
                                   SessionCompaction turnWindow,
                                   SessionCompaction tokenCeiling) {

    public ConversationAdvisors {
        advisors = List.copyOf(advisors);
    }
}
