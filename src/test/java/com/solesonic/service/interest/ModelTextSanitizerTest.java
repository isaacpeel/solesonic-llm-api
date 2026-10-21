package com.solesonic.service.interest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelTextSanitizerTest {

    @Test
    void keepsParagraphsBulletsEmphasisAndCitations() {
        String text = "**Verstappen** won [1].\n\n- *Norris* second [2][3]\n- Leclerc third";

        assertThat(ModelTextSanitizer.sanitize(text)).isEqualTo(text);
    }

    @Test
    void removesEveryKindOfLinkImageAndTag() {
        assertThat(ModelTextSanitizer.sanitize("See [the report](https://evil.example/a).")).isEqualTo("See the report.");
        assertThat(ModelTextSanitizer.sanitize("Pixel ![x](https://evil.example/p.png) here")).isEqualTo("Pixel  here");
        assertThat(ModelTextSanitizer.sanitize("<a href=\"https://evil.example\">click</a>")).isEqualTo("click");
        assertThat(ModelTextSanitizer.sanitize("Go <https://evil.example/c> now")).isEqualTo("Go  now");
        assertThat(ModelTextSanitizer.sanitize("Visit https://evil.example/d or www.evil.example now")).isEqualTo("Visit  or  now");
        assertThat(ModelTextSanitizer.sanitize("Text\n[ref]: https://evil.example/f")).isEqualTo("Text");
    }

    /**
     * What a renderer would autolink with no markup at all: GFM links {@code www.} and scheme URLs
     * after an underscore as well as after a space, and plain email addresses.
     */
    @Test
    void removesWhatARendererWouldAutolinkWithoutMarkup() {
        assertThat(ModelTextSanitizer.sanitize("a _www.evil.example b")).isEqualTo("a _ b");
        assertThat(ModelTextSanitizer.sanitize("a _https://evil.example b")).isEqualTo("a _ b");
        assertThat(ModelTextSanitizer.sanitize("a //evil.example/x b")).isEqualTo("a  b");
        assertThat(ModelTextSanitizer.sanitize("write to someone@evil.example today")).isEqualTo("write to  today");
        assertThat(ModelTextSanitizer.sanitize("run javascript:alert(1) now")).isEqualTo("run  now");
    }

    @Test
    void removesHeadingMarkersAndExtraBlankLines() {
        assertThat(ModelTextSanitizer.sanitize("# Big news\n\n\n\nBody")).isEqualTo("Big news\n\nBody");
    }
}
