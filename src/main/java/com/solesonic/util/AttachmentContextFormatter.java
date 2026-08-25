package com.solesonic.util;

import com.solesonic.model.chat.attachment.ChatAttachmentDescription;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Renders vision-model descriptions of image attachments.
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
            The user attached %d image(s) to the message that follows. You cannot see the images. \
            The descriptions below were produced by a vision model reading the image bytes; treat \
            them as a faithful account of what the images show and answer as though you had seen them.
            """;

    private AttachmentContextFormatter() {
    }

    /**
     * Names the documents rather than carrying them. A document's text reaches the model through
     * retrieval, so what is missing without this block is only the knowledge that the document
     * exists — which is what a question like "summarise the attachment" depends on.
     */
    private static final String DOCUMENT_HEADER =
            "The user attached %d document(s) to the message that follows: %s. "
            + "Their contents have been indexed and the passages relevant to the question appear "
            + "in your retrieved context; you are not being shown the documents in full. Answer "
            + "from the retrieved passages, and say so plainly if they do not cover what was asked.";

    /**
     * Renders the attached-documents block, or {@code null} when the message carried no document
     * that could be indexed.
     * <p>
     * Like {@link #context(List)} this is carried as a message of its own rather than folded into
     * the user's, for the same reason: the retrieval augmenter rewrites the last user message, and
     * anything inside that rewrite competes with the passages it is meant to introduce.
     */
    public static String documentContext(List<String> fileNames) {
        if (CollectionUtils.isEmpty(fileNames)) {
            return null;
        }

        String names = fileNames.stream()
                .map(fileName -> StringUtils.defaultIfBlank(fileName, "untitled"))
                .collect(Collectors.joining(", "));

        return DOCUMENT_HEADER.formatted(fileNames.size(), names);
    }

    /**
     * Renders the described-images block on its own, for callers that carry it as a distinct message
     * adjacent to the user's, rather than folded into the user's own words.
     * <p>
     * Keeping it out of the user message is what stops the RAG advisor from swallowing it: the
     * retrieval augmenter rewrites only the last user message, wrapping it in retrieved context and
     * an instruction to answer from that context alone. Image descriptions inside that wrapper read
     * to the model as more retrieved material and lose to it.
     *
     * @return the block, or {@code null} when there is nothing to describe
     */
    public static String context(List<ChatAttachmentDescription> descriptions) {
        if (CollectionUtils.isEmpty(descriptions)) {
            return null;
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

        return context.toString();
    }

    /**
     * Prepends the described-images block to {@code message}, leaving the user's own words last so
     * the question is the final thing the model reads. Returns {@code message} untouched when there
     * is nothing to add.
     * <p>
     * For the one route that has no message structure to put a separate block into: a remote A2A
     * agent takes a single string.
     */
    public static String prepend(String message, List<ChatAttachmentDescription> descriptions) {
        String context = context(descriptions);

        if (context == null) {
            return message;
        }

        return context + System.lineSeparator() + message;
    }

    private static String heading(int imageNumber, ChatAttachmentDescription description) {
        String fileName = StringUtils.defaultIfBlank(description.fileName(), "untitled");

        if (StringUtils.isBlank(description.description())) {
            return "Image %d — %s:".formatted(imageNumber, fileName);
        }

        return "Image %d — %s (user's note: \"%s\"):".formatted(imageNumber, fileName, description.description());
    }
}
