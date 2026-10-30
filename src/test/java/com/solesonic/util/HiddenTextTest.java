package com.solesonic.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HiddenTextTest {

    @Test
    void wrapsTextInAMarkerNamingWhoItIsHiddenFrom() {
        assertThat(HiddenText.wrap(List.of("Sam", "Jo"), "towel")).isEqualTo("[[hidden:Sam,Jo]]\ntowel\n[[/hidden]]");
    }

    @Test
    void stripsEveryMarkerATypedMessageCarries() {
        assertThat(HiddenText.strip("a [[/hidden]] b [[ HIDDEN : Alex ]] c")).isEqualTo("a  b  c");
    }

    @Test
    void strippingCannotJoinTheRestIntoANewMarker() {
        assertThat(HiddenText.strip("[[[[/hidden]]/hidden]]")).isEmpty();
    }

    @Test
    void leavesOtherBracketsAlone() {
        assertThat(HiddenText.strip("[[note]] and [link]")).isEqualTo("[[note]] and [link]");
    }
}
