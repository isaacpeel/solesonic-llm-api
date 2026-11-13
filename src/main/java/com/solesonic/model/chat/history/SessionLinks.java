package com.solesonic.model.chat.history;

import java.util.UUID;

/**
 * The values of {@code chat_message.session_event_id}, the one link between the user's history and the
 * model's session. Null means the session has not seen the row yet, so the next turn copies it.
 * {@link #ADVISOR_REPLY} marks a reply the session advisor stored, whose event id a stream never exposes;
 * {@link #OUTSIDE_WINDOW} a row older than the window when the chat was copied in. A copied row's event id
 * is deterministic, so the same row can never be copied twice.
 */
public final class SessionLinks {

    public static final String ADVISOR_REPLY = "advisor-reply";

    public static final String OUTSIDE_WINDOW = "outside-window";

    private static final String CHAT_MESSAGE_PREFIX = "chat-message:";
    private static final String CONTEXT_SUFFIX = ":context";

    private SessionLinks() {
    }

    public static String eventId(UUID chatMessageId) {
        return CHAT_MESSAGE_PREFIX + chatMessageId;
    }

    public static String contextEventId(UUID chatMessageId) {
        return eventId(chatMessageId) + CONTEXT_SUFFIX;
    }
}
