package com.solesonic.service.interest;

import com.solesonic.model.interest.NewsSummary;
import com.solesonic.model.interest.NewsSummarySource;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sanitizing, validating and rendering together: what the model writes and what the user is shown.
 */
class NewsSummaryRenderingTest {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 21);
    private static final Pattern ANY_URL = Pattern.compile("(?i)(?:[a-z][a-z0-9+.-]*://|www\\.)[^\\s)>\\]]+");

    private final NewsSummaryValidator newsSummaryValidator = new NewsSummaryValidator(InterestSettingsFixture.settings());
    private final Map<Integer, NewsItem> items = IntStream.rangeClosed(1, 4).boxed()
            .collect(Collectors.toMap(number -> number, NewsSummaryRenderingTest::item));

    /**
     * Neither the model nor an article it read can put a link in front of the user: the only URLs in
     * the rendered message are the kept results' own.
     */
    @Test
    void noLinkTheModelWroteSurvivesAndOnlyKeptResultUrlsAreRendered() {
        String hostile = """
                Verstappen won [1]. See [this](https://evil.example/a) and ![x](https://evil.example/pixel.png).
                <a href="https://evil.example/b">click</a> <https://evil.example/c> https://evil.example/d www.evil.example/e
                [ref]: https://evil.example/f
                # Heading
                """;

        ValidatedSummary validated = newsSummaryValidator.validate(new NewsSummary("Big [win](https://evil.example/g)", hostile,
                List.of(new NewsSummarySource(1, "Race report https://evil.example/h"), new NewsSummarySource(3, "<b>Context</b>"))), 4).orElseThrow();

        String rendered = NewsUpdateRenderer.render("Formula 1", DATE, validated, items);

        assertThat(urls(rendered)).isNotEmpty().allSatisfy(url -> assertThat(url).isIn(items.get(1).url(), items.get(3).url()));
        assertThat(rendered).doesNotContain("evil").doesNotContain("<a").doesNotContain("# Heading");
    }

    /**
     * Link forms a single regex pass cannot recognise — an escaped or nested bracket, a reference
     * definition inside a quote or a list item, an autolink after an underscore, a protocol-relative or
     * scheme target, an email — in the summary, a note and the headline alike. Escaping every punctuation
     * character is what leaves none of them able to form a link.
     */
    @Test
    void noMarkdownSpellingOfALinkSurvivesInAnyModelWrittenField() {
        String bypasses = """
                Escaped [a\\]b](//evil.example/x) and nested [a [b](x) c](//evil.example/y) [1].
                > [x]: //evil.example/z
                - [y]: //evil.example/w
                Then [x] and [y] and ![i\\]j](//evil.example/p.png).
                _www.evil.example and _https://evil.example/q and mail me at someone@evil.example.
                Click javascript:alert(1) or data:text/html,boom.
                [1]: //evil.example/hijack
                """;

        ValidatedSummary validated = newsSummaryValidator.validate(new NewsSummary("Big [a\\]b](//evil.example/h)", bypasses,
                List.of(new NewsSummarySource(1, "Note [n\\]o](//evil.example/n) _www.evil.example"))), 4).orElseThrow();

        String rendered = NewsUpdateRenderer.render("Formula 1", DATE, validated, items);

        assertThat(rendered).doesNotContain("evil").doesNotContain("javascript").doesNotContain("data:");
        assertThat(urls(rendered)).allSatisfy(url -> assertThat(url).isEqualTo(items.get(1).url()));
        assertThat(rendered.lines().filter(line -> line.startsWith(">") || line.matches("^\\s*\\[.*]:.*"))).isEmpty();
    }

    @Test
    void keepsBulletsAndParagraphsButEscapesEverythingElse() {
        ValidatedSummary validated = newsSummaryValidator.validate(new NewsSummary("H",
                "**Verstappen** wins [1].\n\n- Norris second [1]\n  * Leclerc third\n# Not a heading",
                List.of(new NewsSummarySource(1, "n"))), 4).orElseThrow();

        String rendered = NewsUpdateRenderer.render("Formula 1", DATE, validated, items);

        assertThat(rendered)
                .contains("\\*\\*Verstappen\\*\\* wins [[1]](" + items.get(1).url() + ")\\.\n\n- Norris second")
                .contains("\n- Leclerc third")
                .doesNotContain("\n# ");
    }

    @Test
    void renumbersKeptSourcesInTheModelsOrderAndLinksEachCitation() {
        ValidatedSummary validated = newsSummaryValidator.validate(new NewsSummary("Verstappen wins",
                "Verstappen won the race [3]. Norris was second [1][3].",
                List.of(new NewsSummarySource(3, "Race report"), new NewsSummarySource(1, "Standings"))), 4).orElseThrow();

        String rendered = NewsUpdateRenderer.render("Formula 1", DATE, validated, items);

        assertThat(rendered)
                .contains("Verstappen won the race [[1]](" + items.get(3).url() + ")\\.")
                .contains("Norris was second [[2]](" + items.get(1).url() + ")[[1]](" + items.get(3).url() + ")\\.")
                .contains("1. [Story 3](" + items.get(3).url() + ") — site3.example")
                .contains("2. [Story 1](" + items.get(1).url() + ") — site1.example")
                .contains("*Formula 1 · Oct 21, 2026 · 2 sources*");
    }

    @Test
    void aCitationToADroppedSourceIsRemoved() {
        ValidatedSummary validated = newsSummaryValidator.validate(new NewsSummary("Headline", "Kept [2]. Dropped [4]. Unknown [9].",
                List.of(new NewsSummarySource(2, "Note"))), 4).orElseThrow();

        assertThat(validated.summary()).isEqualTo("Kept [2]. Dropped . Unknown .");
    }

    @Test
    void aSummaryWithNoValidCitationRendersTheSourcesListAlone() {
        ValidatedSummary validated = newsSummaryValidator.validate(new NewsSummary("Headline", "Nothing cited here [7].",
                List.of(new NewsSummarySource(2, "Note"))), 4).orElseThrow();

        String rendered = NewsUpdateRenderer.render("Formula 1", DATE, validated, items);

        assertThat(validated.summary()).isNull();
        assertThat(rendered).doesNotContain("Nothing cited").contains("### Sources");
    }

    @Test
    void outOfRangeOrRepeatedIndexesAreDroppedAndNoKeptSourceIsAQuietRun() {
        Optional<ValidatedSummary> validated = newsSummaryValidator.validate(new NewsSummary("H", "S [1]",
                List.of(new NewsSummarySource(0, "a"), new NewsSummarySource(5, "b"), new NewsSummarySource(null, "c"))), 4);

        assertThat(validated).isEmpty();

        ValidatedSummary repeated = newsSummaryValidator.validate(new NewsSummary("H", "S [2]",
                List.of(new NewsSummarySource(2, "a"), new NewsSummarySource(2, "b"))), 4).orElseThrow();

        assertThat(repeated.sources()).singleElement().satisfies(source -> assertThat(source.note()).isEqualTo("a"));
    }

    @Test
    void keepsAtMostTheConfiguredNumberOfSources() {
        List<NewsSummarySource> many = IntStream.rangeClosed(1, 10).mapToObj(index -> new NewsSummarySource(index, "n")).toList();

        ValidatedSummary validated = newsSummaryValidator.validate(new NewsSummary("H", "S [1]", many), 10).orElseThrow();

        assertThat(validated.sources()).hasSize(InterestSettingsFixture.MAX_SOURCES_PER_UPDATE);
    }

    /**
     * A title is third-party text: a link in it is reduced to its words, a bare URL is removed so no
     * renderer can autolink it, and what is left is escaped.
     */
    @Test
    void aTitleCanNeitherLinkNorFormat() {
        NewsItem trap = new NewsItem(1, "Click [here](https://evil.example) or www.evil.example and **bold_name**",
                "https://site.example/a", "site.example", "s", null, 1);
        ValidatedSummary validated = newsSummaryValidator.validate(new NewsSummary("H", "S [1]", List.of(new NewsSummarySource(1, "n"))), 1)
                .orElseThrow();

        String rendered = NewsUpdateRenderer.render("Formula 1", DATE, validated, Map.of(1, trap));

        assertThat(rendered)
                .contains("1. [Click here or and \\*\\*bold\\_name\\*\\*](https://site.example/a) — site.example")
                .doesNotContain("evil");
    }

    @Test
    void namesTheChatFromTheLabelAndDateAlone() {
        assertThat(NewsUpdateRenderer.chatName("Formula 1", DATE)).isEqualTo("Formula 1 · Oct 21");
    }

    /**
     * Every link target in markdown link syntax — an unescaped {@code ](} — plus anything URL-shaped
     * elsewhere.
     */
    private static List<String> urls(String rendered) {
        List<String> urls = new ArrayList<>();
        Matcher linkTargets = Pattern.compile("(?<!\\\\)]\\(([^)]*)\\)").matcher(rendered);

        while (linkTargets.find()) {
            urls.add(linkTargets.group(1));
        }

        Matcher bare = ANY_URL.matcher(rendered.replaceAll("(?<!\\\\)]\\([^)]*\\)", "]"));

        while (bare.find()) {
            urls.add(bare.group());
        }

        return urls;
    }

    private static NewsItem item(int number) {
        return new NewsItem(number, "Story " + number, "https://site" + number + ".example/story", "site" + number + ".example",
                "Snippet " + number, null, 1.0 / number);
    }
}
