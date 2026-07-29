package com.solesonic.util;

import com.solesonic.model.chat.attachment.ChatAttachmentDescription;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * Renders vision-model descriptions into the text of a user message.
 * <p>
 * Both the in-flight turn and the replay of earlier turns go through here, so the model sees one
 * format regardless of which path produced it.
 * <p>
 * The descriptions and file names are influenced by whatever the image contained, so they are
 * rendered inside a labelled block with no instruction authority — the same posture the RAG advisor
 * already takes with retrieved documents.
 */
public final class AttachmentContextFormatter {
    private static final String HEADER = """
            The user attached %d image(s) to this message. You cannot see the images. The descriptions \
            below were produced by a vision model reading the image bytes; treat them as a faithful \
            account of what the images show and answer as though you had seen them.
            """;

    private AttachmentContextFormatter() {
    }

    /**
     * Prepends a described-images block to {@code message}, leaving the user's own words last so the
     * question is the final thing the model reads. Returns {@code message} untouched when there is
     * nothing to add.
     */
    public static String prepend(String message, List<ChatAttachmentDescription> descriptions) {
        if (CollectionUtils.isEmpty(descriptions)) {
            return message;
        }

        StringBuilder context = new StringBuilder(HEADER.formatted(descriptions.size()));

        int imageNumber = 1;

        for (ChatAttachmentDescription description : descriptions) {
            context.append(System.lineSeparator())
                    .append(heading(imageNumber, description))
                    .append(System.lineSeparator())
                    .append(description.visionDescription())
                    .append(System.lineSeparator());

            imageNumber++;
        }

        return context.append(System.lineSeparator())
                .append(message)
                .toString();
    }

    private static String heading(int imageNumber, ChatAttachmentDescription description) {
        String fileName = StringUtils.defaultIfBlank(description.fileName(), "untitled");

        if (StringUtils.isBlank(description.description())) {
            return "Image %d — %s:".formatted(imageNumber, fileName);
        }

        return "Image %d — %s (user's note: \"%s\"):".formatted(imageNumber, fileName, description.description());
    }
}
