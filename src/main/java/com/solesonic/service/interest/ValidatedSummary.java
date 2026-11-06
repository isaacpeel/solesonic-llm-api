package com.solesonic.service.interest;

import java.util.List;

/**
 * A summary that passed {@link NewsSummaryValidator}.
 *
 * @param headline one sanitized line, or null when the model gave none
 * @param summary  sanitized text citing only kept sources, or null when no valid citation was left
 * @param sources  the kept sources, most important first, never empty
 */
public record ValidatedSummary(String headline, String summary, List<KeptSource> sources) {

    /**
     * @param number the number the model was shown for this source
     */
    public record KeptSource(int number, String note) {
    }
}
