package com.solesonic.util;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseSanitizerTest {

    private static String sanitizeAll(String... chunks) {
        StringBuilder result = new StringBuilder();

        Flux.just(chunks)
                .transform(ResponseSanitizer.sanitize())
                .doOnNext(result::append)
                .blockLast();

        return result.toString();
    }

    @Test
    void plainTextPassesThroughUnchanged() {
        StepVerifier.create(Flux.just("Hello ", "world").transform(ResponseSanitizer.sanitize()))
                .expectNext("Hello ")
                .expectNext("world")
                .verifyComplete();
    }

    @Test
    void singleTagInOneChunkBecomesNewline() {
        assertThat(sanitizeAll("line one<br>line two")).isEqualTo("line one\nline two");
    }

    @Test
    void selfClosingAndSpacedVariantsAreRecognized() {
        assertThat(sanitizeAll("a<br/>b<br />c<BR>d")).isEqualTo("a\nb\nc\nd");
    }

    @Test
    void tagSplitAcrossChunksIsStillNormalized() {
        assertThat(sanitizeAll("line one<b", "r>line two")).isEqualTo("line one\nline two");
    }

    @Test
    void tagSplitThreeWaysIsStillNormalized() {
        assertThat(sanitizeAll("line one<", "b", "r>line two")).isEqualTo("line one\nline two");
    }

    @Test
    void unterminatedAngleBracketThatNeverCompletesIsFlushedLiterally() {
        assertThat(sanitizeAll("price < 10")).isEqualTo("price < 10");
    }

    @Test
    void unterminatedTagAtStreamEndIsFlushedLiterally() {
        assertThat(sanitizeAll("done<br")).isEqualTo("done<br");
    }

    @Test
    void emptyFluxProducesNoChunks() {
        StepVerifier.create(Flux.<String>empty().transform(ResponseSanitizer.sanitize()))
                .verifyComplete();
    }
}
