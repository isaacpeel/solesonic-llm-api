package com.solesonic.service.chat;

import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.model.chat.history.ChatSearchMatch;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Finds a search hit in a chat's messages and cuts it down to a snippet that fits on one line.
 * <p>
 * Matching uses {@link String#regionMatches(boolean, int, String, int, int)} on the original text,
 * never a lowercased copy: lowercasing can change a string's length, which would move every offset
 * after it. The snippet starts at most {@link #LEAD_IN} characters before the hit, so cutting off its
 * end never hides the hit.
 */
final class ChatSearchSnippets {
    static final int LEAD_IN = 12;
    static final int MAX_LENGTH = 80;
    static final String ELLIPSIS = "…";

    private ChatSearchSnippets() {
    }

    /**
     * The newest searchable message containing {@code query}, or empty when none does in Java's
     * reading of case — which can disagree with Postgres {@code lower()} for a few characters, and a
     * missing match is better than one pointing at the wrong text.
     */
    static Optional<ChatSearchMatch> match(List<ChatMessage> messages, String query) {
        if (messages == null) {
            return Optional.empty();
        }

        return messages.stream()
                .filter(chatMessage -> chatMessage.getMessageType() != null && chatMessage.getMessage() != null)
                .filter(chatMessage -> ChatService.SEARCHABLE_MESSAGE_TYPES.contains(chatMessage.getMessageType()))
                .sorted(Comparator.comparing(ChatMessage::getTimestamp,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(chatMessage -> match(chatMessage, query))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static Optional<ChatSearchMatch> match(ChatMessage chatMessage, String query) {
        String text = chatMessage.getMessage();
        int hitStart = indexOfIgnoreCase(text, query);

        if (hitStart < 0) {
            return Optional.empty();
        }

        int start = windowStart(text, hitStart);
        int end = windowEnd(text, start);
        int hitEnd = Math.min(hitStart + query.length(), end);

        String prefix = start > 0 ? ELLIPSIS : "";
        String suffix = end < text.length() ? ELLIPSIS : "";
        String body = text.substring(start, end)
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ');

        return Optional.of(new ChatSearchMatch(
                chatMessage.getId(),
                chatMessage.getMessageType(),
                chatMessage.getTimestamp(),
                prefix + body + suffix,
                prefix.length() + hitStart - start,
                hitEnd - hitStart));
    }

    private static int indexOfIgnoreCase(String text, String query) {
        for (int offset = 0; offset + query.length() <= text.length(); offset++) {
            if (text.regionMatches(true, offset, query, 0, query.length())) {
                return offset;
            }
        }

        return -1;
    }

    private static int windowStart(String text, int hitStart) {
        int start = Math.max(0, hitStart - LEAD_IN);

        if (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) {
            for (int index = start; index < hitStart; index++) {
                if (Character.isWhitespace(text.charAt(index))) {
                    return index + 1;
                }
            }
        }

        if (start > 0 && start < hitStart && Character.isLowSurrogate(text.charAt(start))) {
            start++;
        }

        return start;
    }

    private static int windowEnd(String text, int start) {
        int end = Math.min(text.length(), start + MAX_LENGTH);

        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }

        return end;
    }
}
