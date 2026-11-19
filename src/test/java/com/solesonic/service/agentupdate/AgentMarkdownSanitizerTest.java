package com.solesonic.service.agentupdate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMarkdownSanitizerTest {

    @Test
    void keepsHttpsInlineLinksAndPlainMarkdown() {
        String markdown = "## Startups\n\n- **Data Engineer** at Acme: [apply](https://acme.example/careers/42?src=x)\n";

        assertThat(AgentMarkdownSanitizer.markdown(markdown))
                .isEqualTo("## Startups\n\n- **Data Engineer** at Acme: [apply](https://acme.example/careers/42?src=x)");
    }

    @Test
    void aNonHttpsLinkKeepsOnlyItsLabel() {
        assertThat(AgentMarkdownSanitizer.markdown("[apply](http://acme.example/jobs) and [x](javascript:alert)"))
                .isEqualTo("apply and x");
    }

    @Test
    void removesImagesHtmlBareUrlsAndReferenceLinks() {
        String markdown = "![pixel](https://t.example/p.gif)<script>x</script> see www.evil.example or http://evil.example\n"
                + "[ref]: https://evil.example\nmail me@evil.example";

        assertThat(AgentMarkdownSanitizer.markdown(markdown))
                .doesNotContain("pixel", "<script>", "evil.example", "[ref]:")
                .contains("see");
    }

    @Test
    void aLinkLabelCannotSmuggleAnotherLink() {
        assertThat(AgentMarkdownSanitizer.markdown("[see http://evil.example](https://ok.example/a)"))
                .isEqualTo("[see ](https://ok.example/a)");
    }

    @Test
    void aTitleIsOnePlainLineCut() {
        assertThat(AgentMarkdownSanitizer.title("# **New** jobs\n<b>today</b> https://x.example", 120)).isEqualTo("New jobs today");
        assertThat(AgentMarkdownSanitizer.title("a".repeat(200), 10)).hasSize(10).endsWith("…");
    }
}
