package com.solesonic.model.a2a;

import org.a2aproject.sdk.spec.Artifact;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The A2A extension through which the client tells an agent who each message is from in a session
 * several users share, and the agent tells the client whose turn it is. Every message carries the
 * sender and the participants in its metadata; every reply artifact carries the turn in its own.
 * <p>
 * The extension is named in {@code solesonic/extensions} metadata as well as in the message's
 * {@code extensions}, because LiteLLM's gateway may drop the latter.
 */
public final class A2AParticipantsExtension {

    public static final String URI = "urn:solesonic:a2a:extension:participants:v1";
    public static final String EXTENSIONS_KEY = "solesonic/extensions";
    public static final String PARTICIPANT_KEY = "solesonic/participant";
    public static final String PARTICIPANTS_KEY = "solesonic/participants";
    public static final String TURN_KEY = "solesonic/turn";
    public static final String FOR_PARTICIPANTS_KEY = "solesonic/forParticipants";

    private static final String NAME = "name";
    private static final String PARTICIPANTS = "participants";
    private static final String NEXT = "next";
    private static final String OVER = "over";
    private static final Pattern SAFE_NEXT = Pattern.compile("[a-z_]{1,32}");

    private A2AParticipantsExtension() {
    }

    public static Map<String, Object> metadata(String senderName, List<String> participants, List<String> extensions) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(EXTENSIONS_KEY, extensions);
        metadata.put(PARTICIPANT_KEY, Map.of(NAME, senderName));
        metadata.put(PARTICIPANTS_KEY, participants);

        return metadata;
    }

    /**
     * The turn a reply artifact reports. Names are matched to the participants regardless of case and
     * given back in the participant's own spelling; a name that is not a participant is dropped, so an
     * agent can never name someone the session does not know. Empty when the artifact carries no turn,
     * or one that does not hold up.
     */
    public static Optional<A2ATurnState> turn(Artifact artifact, List<String> participants) {
        if (artifact.metadata() == null || !(artifact.metadata().get(TURN_KEY) instanceof Map<?, ?> turn)) {
            return Optional.empty();
        }

        if (!(turn.get(NEXT) instanceof String next) || !SAFE_NEXT.matcher(next).matches()) {
            return Optional.empty();
        }

        boolean over = turn.get(OVER) instanceof Boolean reportedOver && reportedOver;

        return Optional.of(new A2ATurnState(known(turn.get(PARTICIPANTS), participants), next, over));
    }

    /**
     * The participants a form's {@code _meta} says it is for, under the same rules as {@link #turn}.
     */
    public static List<String> forParticipants(Map<String, Object> meta, List<String> participants) {
        if (meta == null) {
            return List.of();
        }

        return known(meta.get(FOR_PARTICIPANTS_KEY), participants);
    }

    private static List<String> known(Object names, List<String> participants) {
        if (!(names instanceof List<?> reported)) {
            return List.of();
        }

        List<String> matched = new ArrayList<>();

        for (Object name : reported) {
            if (!(name instanceof String reportedName)) {
                continue;
            }

            participants.stream()
                    .filter(participant -> participant.toLowerCase(Locale.ROOT).equals(reportedName.toLowerCase(Locale.ROOT)))
                    .findFirst()
                    .filter(participant -> !matched.contains(participant))
                    .ifPresent(matched::add);
        }

        return List.copyOf(matched);
    }
}
