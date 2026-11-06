package com.solesonic.service.interest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InterestTextTest {

    @Test
    void collapsesLineBreaksAndControlCharactersOntoOneLine() {
        assertThat(InterestText.singleLine("  Formula\n1\r\n racing\t\u0000news  ")).isEqualTo("Formula 1 racing news");
    }

    @Test
    void blankIsNull() {
        assertThat(InterestText.singleLine(" \n\t ")).isNull();
        assertThat(InterestText.singleLine(null)).isNull();
    }

    @Test
    void truncatesToTheLimit() {
        assertThat(InterestText.singleLine("x".repeat(130), InterestText.MAX_LABEL_CHARACTERS)).hasSize(InterestText.MAX_LABEL_CHARACTERS);
    }

    @Test
    void normalizesATopicKey() {
        assertThat(InterestText.topicKey("  Sports / Formula_1 Racing!! ")).isEqualTo("sports-formula-1-racing");
        assertThat(InterestText.topicKey("Sports.Formula-1")).isEqualTo("sports.formula-1");
        assertThat(InterestText.topicKey("--..--")).isNull();
        assertThat(InterestText.topicKey("a".repeat(200))).hasSize(InterestText.MAX_TOPIC_KEY_CHARACTERS);
    }
}
