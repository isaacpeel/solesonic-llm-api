package com.solesonic.model.interest;

import java.util.List;

/**
 * What the summary model returns. Untrusted: every field is sanitized and every source index checked
 * by {@code NewsSummaryValidator} before anything is rendered.
 */
public record NewsSummary(String headline, String summary, List<NewsSummarySource> sources) {
}
