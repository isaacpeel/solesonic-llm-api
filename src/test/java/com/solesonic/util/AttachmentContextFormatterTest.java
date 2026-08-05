package com.solesonic.util;

import com.solesonic.model.chat.attachment.ChatAttachmentDescription;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentContextFormatterTest {

    private ChatAttachmentDescription description(String fileName, String description, String visionDescription) {
        return new ChatAttachmentDescription(UUID.randomUUID(), fileName, description, visionDescription);
    }

    @Test
    void prependReturnsTheMessageUnchangedWithoutDescriptions() {
        assertThat(AttachmentContextFormatter.prepend("what is this?", List.of()))
                .isEqualTo("what is this?");
    }

    @Test
    void prependReturnsTheMessageUnchangedForANullList() {
        assertThat(AttachmentContextFormatter.prepend("what is this?", null))
                .isEqualTo("what is this?");
    }

    @Test
    void prependNumbersImagesInListOrder() {
        String prompt = AttachmentContextFormatter.prepend("compare these", List.of(
                description("first.png", null, "a bar chart"),
                description("second.png", null, "a line chart")));

        assertThat(prompt)
                .contains("Image 1 — first.png:")
                .contains("a bar chart")
                .contains("Image 2 — second.png:")
                .contains("a line chart");

        assertThat(prompt.indexOf("Image 1")).isLessThan(prompt.indexOf("Image 2"));
    }

    @Test
    void prependIncludesTheClientSuppliedNoteWhenPresent() {
        String prompt = AttachmentContextFormatter.prepend("why?", List.of(
                description("error.png", "the error I get on save", "a red validation banner")));

        assertThat(prompt).contains("Image 1 — error.png (user's note: \"the error I get on save\"):");
    }

    @Test
    void prependOmitsTheNoteWhenBlank() {
        String prompt = AttachmentContextFormatter.prepend("why?", List.of(
                description("error.png", "   ", "a red validation banner")));

        assertThat(prompt).contains("Image 1 — error.png:");
        assertThat(prompt).doesNotContain("user's note");
    }

    @Test
    void prependNamesAnUntitledImageWithoutAFileName() {
        String prompt = AttachmentContextFormatter.prepend("why?", List.of(
                description(null, null, "a red validation banner")));

        assertThat(prompt).contains("Image 1 — untitled:");
    }

    @Test
    void prependLeavesTheUsersQuestionLast() {
        String prompt = AttachmentContextFormatter.prepend("what is this?", List.of(
                description("screenshot.png", null, "a login screen")));

        assertThat(prompt).endsWith("what is this?");
        assertThat(prompt.indexOf("a login screen")).isLessThan(prompt.indexOf("what is this?"));
    }

    @Test
    void prependCountsTheImagesInTheHeader() {
        String prompt = AttachmentContextFormatter.prepend("compare these", List.of(
                description("first.png", null, "a bar chart"),
                description("second.png", null, "a line chart")));

        assertThat(prompt).startsWith("The user attached 2 image(s) to the message that follows.");
    }

    @Test
    void contextRendersTheBlockWithoutTheUserMessage() {
        String context = AttachmentContextFormatter.context(List.of(
                description("screenshot.png", null, "a login screen")));

        assertThat(context)
                .contains("Image 1 — screenshot.png:")
                .contains("a login screen")
                .doesNotContain("what is this?");
    }

    /**
     * Null rather than an empty string: callers use it to decide whether to add a message at all,
     * and an empty user message would reach the model as a blank turn.
     */
    @Test
    void contextIsNullWithoutDescriptions() {
        assertThat(AttachmentContextFormatter.context(List.of())).isNull();
        assertThat(AttachmentContextFormatter.context(null)).isNull();
    }
}
