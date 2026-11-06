package com.solesonic.service.interest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NewsUrlNormalizerTest {

    @Test
    void collapsesTrackingFragmentCaseAndTrailingSlashVariantsToOneSpelling() {
        String expected = "https://news.example.com/f1/race?id=7";

        assertThat(NewsUrlNormalizer.normalize("https://News.Example.com/f1/race/?id=7")).contains(expected);
        assertThat(NewsUrlNormalizer.normalize("https://news.example.com/f1/race?utm_source=x&id=7&utm_medium=y#comments")).contains(expected);
        assertThat(NewsUrlNormalizer.normalize("https://news.example.com/f1/race?fbclid=abc&id=7&gclid=def")).contains(expected);
    }

    @Test
    void refusesAnythingThatIsNotAWebLink() {
        assertThat(NewsUrlNormalizer.normalize("javascript:alert(1)")).isEmpty();
        assertThat(NewsUrlNormalizer.normalize("ftp://example.com/file")).isEmpty();
        assertThat(NewsUrlNormalizer.normalize("data:text/html,hello")).isEmpty();
        assertThat(NewsUrlNormalizer.normalize("https://user:password@example.com/")).isEmpty();
        assertThat(NewsUrlNormalizer.normalize("not a url")).isEmpty();
        assertThat(NewsUrlNormalizer.normalize(null)).isEmpty();
    }

    /**
     * The extraction tool fetches these URLs, so a poisoned result must not point it at a private
     * network.
     */
    @Test
    void refusesInternalHostsIpLiteralsAndExplicitPorts() {
        assertThat(NewsUrlNormalizer.normalize("http://169.254.169.254/latest/meta-data")).isEmpty();
        assertThat(NewsUrlNormalizer.normalize("http://10.0.0.5/admin")).isEmpty();
        assertThat(NewsUrlNormalizer.normalize("http://[::1]/")).isEmpty();
        assertThat(NewsUrlNormalizer.normalize("http://localhost/")).isEmpty();
        assertThat(NewsUrlNormalizer.normalize("http://printer.local/")).isEmpty();
        assertThat(NewsUrlNormalizer.normalize("http://vault.internal/")).isEmpty();
        assertThat(NewsUrlNormalizer.normalize("https://news.example.com:8443/story")).isEmpty();
    }

    /**
     * A trailing dot resolves exactly like the name without it, and a resolver reads a hex or numeric
     * last label as an address.
     */
    @Test
    void refusesInternalHostsSpelledWithATrailingDotOrAsANonDecimalAddress() {
        assertThat(NewsUrlNormalizer.normalize("http://localhost./")).isEmpty();
        assertThat(NewsUrlNormalizer.normalize("http://vault.internal./")).isEmpty();
        assertThat(NewsUrlNormalizer.normalize("http://127.0.0.1./")).isEmpty();
        assertThat(NewsUrlNormalizer.normalize("http://0x7f.0.0.1/")).isEmpty();
        assertThat(NewsUrlNormalizer.normalize("http://0x7f.0x1/")).isEmpty();
        assertThat(NewsUrlNormalizer.normalize("http://127.1/")).isEmpty();
    }

    @Test
    void dropsATrailingDotFromAPublicName() {
        assertThat(NewsUrlNormalizer.normalize("https://news.example.com./story")).contains("https://news.example.com/story");
    }

    @Test
    void hashesToSixtyFourHexCharactersAndOneHashPerSpelling() {
        String hash = NewsUrlNormalizer.hash("https://news.example.com/f1/race");

        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
        assertThat(NewsUrlNormalizer.hash("https://news.example.com/f1/race")).isEqualTo(hash);
    }

    @Test
    void namesTheHostWithoutWww() {
        assertThat(NewsUrlNormalizer.host("https://www.bbc.co.uk/sport")).isEqualTo("bbc.co.uk");
    }
}
