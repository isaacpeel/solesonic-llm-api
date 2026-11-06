package com.solesonic.service.chat;

import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.model.chat.history.ChatSearchMatch;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.MessageType;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChatSearchSnippetsTest {
    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-10-24T09:12:03-06:00");

    @Test
    void choosesTheNewestMatchingMessageWhateverOrderTheyArriveIn() {
        ChatMessage older = message(MessageType.USER, "unifi switch question", NOW);
        ChatMessage newer = message(MessageType.ASSISTANT, "the unifi switch answer", NOW.plusMinutes(2));

        ChatSearchMatch match = ChatSearchSnippets.match(List.of(older, newer), "unifi").orElseThrow();

        assertThat(match.chatMessageId()).isEqualTo(newer.getId());
        assertThat(match.messageType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(match.timestamp()).isEqualTo(newer.getTimestamp());
    }

    @Test
    void neverChoosesSystemOrToolMessages() {
        ChatMessage user = message(MessageType.USER, "unifi", NOW);
        ChatMessage system = message(MessageType.SYSTEM, "{\"unifi\": true}", NOW.plusMinutes(1));
        ChatMessage tool = message(MessageType.TOOL, "unifi tool output", NOW.plusMinutes(2));

        ChatSearchMatch match = ChatSearchSnippets.match(List.of(user, system, tool), "unifi").orElseThrow();

        assertThat(match.chatMessageId()).isEqualTo(user.getId());
    }

    @Test
    void anEarlyHitHasNoLeadingEllipsis() {
        ChatSearchMatch match = only("set up the unifi switch", "unifi");

        assertThat(match.snippet()).isEqualTo("set up the unifi switch");
        assertThat(highlighted(match)).isEqualTo("unifi");
    }

    @Test
    void aDeepHitStartsAtAWordBoundaryBehindAnEllipsis() {
        String text = "This is a fairly long preamble before we finally set up the unifi POE switch";

        ChatSearchMatch match = only(text, "unifi");

        assertThat(match.snippet()).isEqualTo("…set up the unifi POE switch");
        assertThat(match.highlightStart()).isEqualTo(12);
        assertThat(highlighted(match)).isEqualTo("unifi");
    }

    @Test
    void theLeadInIsAtMostTwelveCharactersWhenThereIsNoWordBoundary() {
        String text = "x".repeat(40) + "unifi";

        ChatSearchMatch match = only(text, "unifi");

        assertThat(match.snippet()).isEqualTo("…" + "x".repeat(12) + "unifi");
        assertThat(highlighted(match)).isEqualTo("unifi");
    }

    @Test
    void aLongTailIsCutToEightyCharactersAndAnEllipsis() {
        String text = "unifi " + "y".repeat(200);

        ChatSearchMatch match = only(text, "unifi");

        assertThat(match.snippet()).hasSize(81).endsWith("…");
        assertThat(match.snippet()).startsWith("unifi ");
        assertThat(highlighted(match)).isEqualTo("unifi");
    }

    @Test
    void lineBreaksAndTabsBecomeSpacesWithoutMovingTheHighlight() {
        ChatSearchMatch match = only("one\r\n\tthe unifi", "unifi");

        assertThat(match.snippet()).isEqualTo("one   the unifi");
        assertThat(match.snippet()).doesNotContain("\n", "\r", "\t");
        assertThat(highlighted(match)).isEqualTo("unifi");
    }

    @Test
    void matchesIgnoringCaseAndHighlightsTheOriginalText() {
        ChatSearchMatch match = only("Set up the UniFi switch", "unifi");

        assertThat(highlighted(match)).isEqualTo("UniFi");
    }

    /**
     * {@code "İ".toLowerCase()} is two characters long, so offsets taken from a lowercased copy would
     * point past the hit.
     */
    @Test
    void offsetsStayInTheOriginalTextWhenLowercasingChangesItsLength() {
        ChatSearchMatch match = only("İİİ unifi", "UNIFI");

        assertThat(match.highlightStart()).isEqualTo(4);
        assertThat(highlighted(match)).isEqualTo("unifi");
    }

    @Test
    void offsetsAreUtf16AfterEmojiAndCjk() {
        ChatSearchMatch match = only("😀 设置 unifi", "unifi");

        assertThat(match.highlightStart()).isEqualTo("😀 设置 ".length());
        assertThat(highlighted(match)).isEqualTo("unifi");
    }

    @Test
    void neverStartsTheSnippetHalfwayThroughASurrogatePair() {
        String text = "a" + "😀".repeat(20) + "-unifi";

        ChatSearchMatch match = only(text, "unifi");

        String body = match.snippet().substring(1);
        assertThat(Character.isLowSurrogate(body.charAt(0))).isFalse();
        assertThat(highlighted(match)).isEqualTo("unifi");
    }

    @Test
    void aQueryLongerThanTheSnippetHighlightsOnlyWhatFits() {
        String query = "z".repeat(100);

        ChatSearchMatch match = only(query, query);

        assertThat(match.highlightStart()).isZero();
        assertThat(match.highlightLength()).isEqualTo(80);
        assertThat(match.snippet()).isEqualTo("z".repeat(80) + "…");
    }

    @Test
    void noHitInAnyMessageIsEmpty() {
        ChatMessage chatMessage = message(MessageType.USER, "nothing relevant", NOW);

        assertThat(ChatSearchSnippets.match(List.of(chatMessage), "unifi")).isEmpty();
    }

    @Test
    void noMessagesIsEmpty() {
        assertThat(ChatSearchSnippets.match(null, "unifi")).isEmpty();
        assertThat(ChatSearchSnippets.match(List.of(message(MessageType.USER, null, NOW)), "unifi")).isEmpty();
    }

    private static ChatSearchMatch only(String text, String query) {
        Optional<ChatSearchMatch> match = ChatSearchSnippets.match(List.of(message(MessageType.USER, text, NOW)), query);

        return match.orElseThrow();
    }

    private static String highlighted(ChatSearchMatch match) {
        return match.snippet().substring(match.highlightStart(), match.highlightStart() + match.highlightLength());
    }

    private static ChatMessage message(MessageType messageType, String text, ZonedDateTime timestamp) {
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setId(UUID.randomUUID());
        chatMessage.setMessageType(messageType);
        chatMessage.setMessage(text);
        chatMessage.setTimestamp(timestamp);

        return chatMessage;
    }
}
