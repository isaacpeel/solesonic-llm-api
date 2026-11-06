package com.solesonic.service.interest;

import com.solesonic.service.interest.ValidatedSummary.KeptSource;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a validated summary as the one assistant message of a news chat. Every link in the output is
 * built here, from a search result's normalized URL, and the model can only point at one by number:
 * kept sources are renumbered 1..k in the order the model ranked them and each {@code [n]} becomes
 * {@code [[k]](url)}.
 * <p>
 * Text the model or a web page wrote is rendered as plain text: every ASCII punctuation character in
 * it is backslash-escaped, which CommonMark allows for any of them, so no link, image, reference
 * definition, HTML, heading, quote or table can form however it was spelled. The only structure kept
 * is paragraphs and {@code - } bullets. Text a renderer would autolink without any markup is removed
 * beforehand by {@link ModelTextSanitizer}.
 */
public final class NewsUpdateRenderer {
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);

    private static final DateTimeFormatter CHAT_DATE = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
    private static final Pattern BULLET = Pattern.compile("^[-*+]\\s+");

    private NewsUpdateRenderer() {
    }

    /**
     * @param itemsByNumber the items the model was shown, by the number it saw
     */
    public static String render(String label, LocalDate date, ValidatedSummary validatedSummary, Map<Integer, NewsItem> itemsByNumber) {
        List<KeptSource> sources = validatedSummary.sources();
        Map<Integer, String> citationLinks = new HashMap<>();

        for (int index = 0; index < sources.size(); index++) {
            int number = sources.get(index).number();
            citationLinks.put(number, "[[" + (index + 1) + "]](" + linkTarget(itemsByNumber.get(number).url()) + ")");
        }

        String plainLabel = plain(label);
        String headline = validatedSummary.headline() == null ? plainLabel : plain(validatedSummary.headline());
        int count = sources.size();

        StringBuilder rendered = new StringBuilder()
                .append("## ").append(headline).append("\n\n")
                .append('*').append(plainLabel).append(" · ").append(DATE.format(date)).append(" · ")
                .append(count).append(count == 1 ? " source" : " sources").append("*\n\n");

        if (validatedSummary.summary() != null) {
            rendered.append(summary(validatedSummary.summary(), citationLinks)).append("\n\n");
        }

        rendered.append("### Sources\n\n");

        for (int index = 0; index < count; index++) {
            KeptSource source = sources.get(index);
            NewsItem item = itemsByNumber.get(source.number());
            String title = InterestText.singleLine(ModelTextSanitizer.sanitize(item.title()));

            rendered.append(index + 1).append(". [").append(title == null ? item.host() : escape(title)).append("](")
                    .append(linkTarget(item.url())).append(") — ").append(item.host()).append('\n');

            if (!source.note().isEmpty()) {
                rendered.append("   ").append(plain(source.note())).append('\n');
            }
        }

        return rendered.toString().trim();
    }

    /**
     * A chat name built from the label and the date alone, never from model text.
     */
    public static String chatName(String label, LocalDate date) {
        return label + " · " + CHAT_DATE.format(date);
    }

    /**
     * Each line on its own, leading indentation dropped so nothing becomes a code block; a leading
     * bullet is kept as {@code - }, everything else is escaped, and each citation becomes its link.
     */
    private static String summary(String summary, Map<Integer, String> citationLinks) {
        StringBuilder rendered = new StringBuilder();

        for (String rawLine : summary.split("\n", -1)) {
            String line = rawLine.strip();
            Matcher bullet = BULLET.matcher(line);

            if (bullet.find()) {
                rendered.append("- ");
                line = line.substring(bullet.end());
            }

            rendered.append(withCitations(line, citationLinks)).append('\n');
        }

        return rendered.toString().trim();
    }

    private static String withCitations(String line, Map<Integer, String> citationLinks) {
        StringBuilder rendered = new StringBuilder();
        Matcher citation = NewsSummaryValidator.CITATION.matcher(line);
        int position = 0;

        while (citation.find()) {
            rendered.append(escape(line.substring(position, citation.start())));

            String link = citationLinks.get(Integer.parseInt(citation.group(1)));
            rendered.append(link == null ? "" : link);

            position = citation.end();
        }

        return rendered.append(escape(line.substring(position))).toString();
    }

    private static String plain(String text) {
        return escape(ModelTextSanitizer.sanitize(text));
    }

    /**
     * Backslash-escapes every ASCII punctuation character. Applied after {@link ModelTextSanitizer},
     * which has already removed anything a renderer's autolinking would turn into a link.
     */
    static String escape(String text) {
        StringBuilder escaped = new StringBuilder(text.length());

        for (char character : text.toCharArray()) {
            if (isAsciiPunctuation(character)) {
                escaped.append('\\');
            }

            escaped.append(character);
        }

        return escaped.toString();
    }

    private static boolean isAsciiPunctuation(char character) {
        return (character >= '!' && character <= '/')
                || (character >= ':' && character <= '@')
                || (character >= '[' && character <= '`')
                || (character >= '{' && character <= '~');
    }

    /**
     * A normalized URL cannot hold a space, but it can hold parentheses and angle brackets, any of
     * which would end a markdown link target early.
     */
    private static String linkTarget(String url) {
        return url.replace("(", "%28").replace(")", "%29").replace("<", "%3C").replace(">", "%3E").replace(" ", "%20");
    }
}
