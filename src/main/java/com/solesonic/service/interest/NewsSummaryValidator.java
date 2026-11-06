package com.solesonic.service.interest;

import com.solesonic.config.interest.InterestSettings;
import com.solesonic.model.interest.NewsSummary;
import com.solesonic.model.interest.NewsSummarySource;
import com.solesonic.service.interest.ValidatedSummary.KeptSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The code-side guard on what the summary model wrote. A source counts only if its index is one of
 * the numbers the model was shown, once; an input left out of {@code sources} is treated as
 * irrelevant. All model text is sanitized, and a citation to anything not kept is removed.
 */
@Component
public class NewsSummaryValidator {
    static final int MAX_HEADLINE_CHARACTERS = 150;
    static final int MAX_NOTE_CHARACTERS = 200;

    static final Pattern CITATION = Pattern.compile("\\[(\\d{1,3})]");

    private final int maxSourcesPerUpdate;
    private final int maxSummaryCharacters;

    public NewsSummaryValidator(InterestSettings interestSettings) {
        this.maxSourcesPerUpdate = interestSettings.maxSourcesPerUpdate();
        this.maxSummaryCharacters = interestSettings.maxSummaryCharacters();
    }

    /**
     * @param shownCount how many numbered sources the model was shown, numbered from 1
     * @return empty when no source was kept, which makes the run a quiet one
     */
    public Optional<ValidatedSummary> validate(NewsSummary newsSummary, int shownCount) {
        if (newsSummary == null || newsSummary.sources() == null) {
            return Optional.empty();
        }

        Set<Integer> keptNumbers = new LinkedHashSet<>();
        List<KeptSource> kept = new ArrayList<>();

        for (NewsSummarySource source : newsSummary.sources()) {
            if (kept.size() >= maxSourcesPerUpdate) {
                break;
            }

            if (source == null || source.index() == null || source.index() < 1 || source.index() > shownCount || !keptNumbers.add(source.index())) {
                continue;
            }

            String note = InterestText.singleLine(ModelTextSanitizer.sanitize(source.note()), MAX_NOTE_CHARACTERS);
            kept.add(new KeptSource(source.index(), Objects.requireNonNullElse(note, "")));
        }

        if (kept.isEmpty()) {
            return Optional.empty();
        }

        String headline = InterestText.singleLine(ModelTextSanitizer.sanitize(newsSummary.headline()), MAX_HEADLINE_CHARACTERS);

        return Optional.of(new ValidatedSummary(headline, summary(newsSummary.summary(), keptNumbers), List.copyOf(kept)));
    }

    private String summary(String text, Set<Integer> keptNumbers) {
        String sanitized = ModelTextSanitizer.sanitize(text);

        if (sanitized.length() > maxSummaryCharacters) {
            sanitized = sanitized.substring(0, maxSummaryCharacters).trim();
        }

        AtomicBoolean cited = new AtomicBoolean();

        String citedOnly = CITATION.matcher(sanitized).replaceAll(match -> {
            if (keptNumbers.contains(Integer.parseInt(match.group(1)))) {
                cited.set(true);
                return Matcher.quoteReplacement(match.group());
            }

            return "";
        });

        return cited.get() ? citedOnly.trim() : null;
    }
}
