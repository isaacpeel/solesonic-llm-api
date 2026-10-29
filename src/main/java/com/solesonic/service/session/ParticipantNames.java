package com.solesonic.service.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The names participants are known by in a shared session: the only identity the agent ever learns,
 * and the names the other participants see. Each name is already in the agent's own name form, so the
 * agent's check that a name is clean holds: letters, digits, underscores, spaces and {@code '.-},
 * single spaces, trimmed, within {@code maxCharacters}. Names are unique ignoring case.
 */
public final class ParticipantNames {
    private static final Pattern NOT_NAME_CHARACTERS = Pattern.compile("[^\\p{L}\\p{N}_ '.-]");
    private static final Pattern SPACES = Pattern.compile(" +");
    private static final String FALLBACK = "Participant ";

    private ParticipantNames() {
    }

    /**
     * What a participant is known by, best first: their display name, then their username.
     */
    public record Candidate(String displayName, String username) {
    }

    /**
     * One name per candidate, in seat order. A name an earlier seat already uses gets the participant's
     * username initial, and failing that their seat number.
     */
    public static List<String> assign(List<Candidate> candidates, int maxCharacters) {
        List<String> names = new ArrayList<>();

        for (int seat = 0; seat < candidates.size(); seat++) {
            Candidate candidate = candidates.get(seat);
            String name = firstNamed(maxCharacters, candidate.displayName(), candidate.username(), FALLBACK + (seat + 1));

            names.add(unique(name, candidate, seat, names, maxCharacters));
        }

        return List.copyOf(names);
    }

    /**
     * The agent's own cleaning, rule for rule: characters outside the name set are removed, runs of
     * spaces become one, and the result is cut to {@code maxCharacters} and trimmed.
     */
    public static String clean(String raw, int maxCharacters) {
        if (raw == null) {
            return "";
        }

        String name = SPACES.matcher(NOT_NAME_CHARACTERS.matcher(raw).replaceAll("").strip()).replaceAll(" ");

        if (name.codePointCount(0, name.length()) > maxCharacters) {
            name = name.substring(0, name.offsetByCodePoints(0, maxCharacters));
        }

        return name.strip();
    }

    private static String firstNamed(int maxCharacters, String... candidates) {
        for (String candidate : candidates) {
            String name = clean(candidate, maxCharacters);

            if (hasLetterOrDigit(name)) {
                return name;
            }
        }

        return clean(FALLBACK, maxCharacters);
    }

    private static String unique(String name, Candidate candidate, int seat, List<String> taken, int maxCharacters) {
        if (isFree(name, taken)) {
            return name;
        }

        String initial = clean(candidate.username(), maxCharacters);

        if (!initial.isEmpty() && Character.isLetterOrDigit(initial.codePointAt(0))) {
            String withInitial = withSuffix(name, " " + initial.substring(0, initial.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT) + ".", maxCharacters);

            if (isFree(withInitial, taken)) {
                return withInitial;
            }
        }

        return withSuffix(name, " " + (seat + 1), maxCharacters);
    }

    private static String withSuffix(String name, String suffix, int maxCharacters) {
        int room = Math.max(maxCharacters - suffix.length(), 1);

        return clean(clean(name, room) + suffix, maxCharacters);
    }

    private static boolean isFree(String name, List<String> taken) {
        return taken.stream().noneMatch(existing -> existing.equalsIgnoreCase(name));
    }

    private static boolean hasLetterOrDigit(String name) {
        return name.codePoints().anyMatch(Character::isLetterOrDigit);
    }
}
