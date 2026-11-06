package com.solesonic.model.chat.history;

import org.springframework.ai.chat.messages.MessageType;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Where a chat search found its hit: the message, and a one-line snippet of it. The highlight is a
 * pair of UTF-16 offsets into {@code snippet}, which index a JavaScript string the same way.
 */
public record ChatSearchMatch(
        UUID chatMessageId,
        MessageType messageType,
        ZonedDateTime timestamp,
        String snippet,
        int highlightStart,
        int highlightLength) {
}
