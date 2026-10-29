package com.solesonic.model.a2a;

import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class A2AParticipantsExtensionTest {
    private static final List<String> PARTICIPANTS = List.of("Alex", "Sam");

    private static Artifact reply(Map<String, Object> metadata) {
        return Artifact.builder().artifactId("reply").parts(new TextPart("reply")).metadata(metadata).build();
    }

    private static Map<String, Object> turn(Object participants, Object next, Object over) {
        return Map.of(A2AParticipantsExtension.TURN_KEY, Map.of("participants", participants, "next", next, "over", over));
    }

    @Test
    void aTurnIsReadInTheParticipantsOwnSpelling() {
        assertThat(A2AParticipantsExtension.turn(reply(turn(List.of("SAM", "sam"), "answer", false)), PARTICIPANTS))
                .contains(new A2ATurnState(List.of("Sam"), "answer", false));
    }

    @Test
    void aNameThatIsNotAParticipantIsDropped() {
        assertThat(A2AParticipantsExtension.turn(reply(turn(List.of("Kim", "Alex", 7), "steal", false)), PARTICIPANTS))
                .contains(new A2ATurnState(List.of("Alex"), "steal", false));
    }

    @Test
    void aNextThatIsNotAWordIsRefused() {
        assertThat(A2AParticipantsExtension.turn(reply(turn(List.of("Alex"), "<script>", false)), PARTICIPANTS)).isEmpty();
        assertThat(A2AParticipantsExtension.turn(reply(turn(List.of("Alex"), 3, false)), PARTICIPANTS)).isEmpty();
    }

    @Test
    void overIsTrueOnlyWhenSaidSo() {
        assertThat(A2AParticipantsExtension.turn(reply(turn(List.of(), "rematch", "yes")), PARTICIPANTS))
                .contains(new A2ATurnState(List.of(), "rematch", false));
    }

    @Test
    void anArtifactWithoutATurnHasNone() {
        assertThat(A2AParticipantsExtension.turn(reply(null), PARTICIPANTS)).isEmpty();
        assertThat(A2AParticipantsExtension.turn(reply(Map.of("solesonic/sound", Map.of())), PARTICIPANTS)).isEmpty();
    }

    @Test
    void aFormNamesWhoItIsFor() {
        assertThat(A2AParticipantsExtension.forParticipants(Map.of(A2AParticipantsExtension.FOR_PARTICIPANTS_KEY, List.of("alex")), PARTICIPANTS))
                .containsExactly("Alex");
        assertThat(A2AParticipantsExtension.forParticipants(null, PARTICIPANTS)).isEmpty();
    }

    @Test
    void theMetadataNamesTheSenderTheParticipantsAndTheExtensions() {
        assertThat(A2AParticipantsExtension.metadata("Sam", PARTICIPANTS, List.of(A2AParticipantsExtension.URI)))
                .containsEntry("solesonic/participant", Map.of("name", "Sam"))
                .containsEntry("solesonic/participants", PARTICIPANTS)
                .containsEntry("solesonic/extensions", List.of(A2AParticipantsExtension.URI));
    }
}
