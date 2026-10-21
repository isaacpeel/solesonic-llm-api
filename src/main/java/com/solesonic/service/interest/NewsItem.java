package com.solesonic.service.interest;

/**
 * One new search result as shown to the summary model.
 *
 * @param number the number the model cites it by, from 1
 * @param url    the normalized URL; the only URL ever rendered for it
 * @param text   the extracted article, or null when only the snippet is available
 */
public record NewsItem(int number,
                       String title,
                       String url,
                       String host,
                       String snippet,
                       String text,
                       double relevanceScore) {

    public String body() {
        return text == null || text.isBlank() ? snippet : text;
    }
}
