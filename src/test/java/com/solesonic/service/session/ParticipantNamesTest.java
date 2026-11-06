package com.solesonic.service.session;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Participant names must already be in the agent's own clean-name form, and unique ignoring case, or
 * the agent refuses the whole session as invalid participants.
 */
class ParticipantNamesTest {

    private static final int MAX = 40;

    private static ParticipantNames.Candidate candidate(String displayName, String username) {
        return new ParticipantNames.Candidate(displayName, username);
    }

    @Test
    void theDisplayNameComesFirst() {
        assertThat(ParticipantNames.assign(List.of(candidate("Alex", "alex99"), candidate("Sam", "sam")), MAX))
                .containsExactly("Alex", "Sam");
    }

    @Test
    void theUsernameStandsInForAMissingDisplayName() {
        assertThat(ParticipantNames.assign(List.of(candidate(null, "alex99"), candidate("  ", "sam")), MAX))
                .containsExactly("alex99", "sam");
    }

    @Test
    void aParticipantWithNoNameAtAllIsNumbered() {
        assertThat(ParticipantNames.assign(List.of(candidate(null, null), candidate("<>", "**")), MAX))
                .containsExactly("Participant 1", "Participant 2");
    }

    @Test
    void charactersOutsideTheNameSetAreRemovedAndSpacesCollapsed() {
        assertThat(ParticipantNames.clean("  Al<b>ex   O'Brien-Smith!  ", MAX)).isEqualTo("Albex O'Brien-Smith");
        assertThat(ParticipantNames.clean("Zoë Ñúñez", MAX)).isEqualTo("Zoë Ñúñez");
    }

    @Test
    void aLongNameIsCutToTheLimitAndTrimmed() {
        assertThat(ParticipantNames.clean("Alexandra Bartholomew", 10)).isEqualTo("Alexandra");
    }

    @Test
    void aNameIsUniqueIgnoringCaseWithTheUsernameInitial() {
        assertThat(ParticipantNames.assign(List.of(candidate("Alex", "abrown"), candidate("alex", "jsmith")), MAX))
                .containsExactly("Alex", "alex J.");
    }

    @Test
    void theSeatNumberIsTheLastResort() {
        assertThat(ParticipantNames.assign(List.of(candidate("Alex J.", "jones"), candidate("Alex", "jsmith"), candidate("Alex", "jo")), MAX))
                .containsExactly("Alex J.", "Alex", "Alex 3");
    }

    @Test
    void cleaningIsStable() {
        String name = ParticipantNames.clean("  Mary   Ann  ", MAX);

        assertThat(ParticipantNames.clean(name, MAX)).isEqualTo(name).isEqualTo("Mary Ann");
    }

    @Test
    void aNewcomerKeepsTheirDisplayNameWhenNoOneHasIt() {
        assertThat(ParticipantNames.nameNewcomer(candidate("Kim", "kim"), 2, List.of("Alex", "Sam"), MAX)).isEqualTo("Kim");
    }

    @Test
    void aNewcomerWhoseNameIsTakenIgnoringCaseGetsTheirUsernameInitial() {
        assertThat(ParticipantNames.nameNewcomer(candidate("sam", "kim"), 2, List.of("Alex", "Sam"), MAX)).isEqualTo("sam K.");
    }

    @Test
    void aNewcomerWhoseInitialFormIsAlsoTakenGetsASeatNumber() {
        assertThat(ParticipantNames.nameNewcomer(candidate("Sam", "kim"), 2, List.of("Alex", "Sam", "Sam K."), MAX)).isEqualTo("Sam 3");
    }

    @Test
    void aNewcomerNeverGetsANameAnyExistingRowHolds() {
        assertThat(ParticipantNames.nameNewcomer(candidate("Sam", null), 2, List.of("Sam", "Sam 3", "Sam 4"), MAX))
                .isNotIn("Sam", "Sam 3", "Sam 4");
    }

    @Test
    void aNewcomerWithNoNameAtAllIsNumberedBySeat() {
        assertThat(ParticipantNames.nameNewcomer(candidate(null, null), 2, List.of("Alex", "Sam"), MAX)).isEqualTo("Participant 3");
    }
}
