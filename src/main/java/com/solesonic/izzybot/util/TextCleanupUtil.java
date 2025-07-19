package com.solesonic.izzybot.util;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Utility class for text cleanup operations.
 * Provides methods to clean and normalize text for various purposes.
 */
@Component
public class TextCleanupUtil {

    /**
     * Removes all special characters from the input text, leaving only alphanumeric characters and spaces.
     *
     * @param text the text to clean
     * @return the cleaned text with only alphanumeric characters and spaces
     */
    public String removeSpecialCharacters(String text) {
        if (text == null) {
            return null;
        }
        if (StringUtils.isBlank(text)) {
            return text;
        }
        return text.replaceAll("[^a-zA-Z0-9\\s]", "");
    }

    /**
     * Normalizes whitespace in the input text by replacing multiple spaces with a single space
     * and trimming leading and trailing whitespace.
     *
     * @param text the text to normalize
     * @return the normalized text with consistent whitespace
     */
    public String normalizeWhitespace(String text) {
        if (text == null) {
            return null;
        }
        if (StringUtils.isBlank(text)) {
            return "";
        }
        return text.trim().replaceAll("\\s+", " ");
    }

    /**
     * Removes HTML tags from the input text.
     *
     * @param text the text containing HTML tags
     * @return the text with HTML tags removed
     */
    public String removeHtmlTags(String text) {
        if (text == null) {
            return null;
        }
        if (StringUtils.isBlank(text)) {
            return text;
        }
        return text.replaceAll("<[^>]*>", "");
    }

    /**
     * Removes URLs from the input text.
     *
     * @param text the text containing URLs
     * @return the text with URLs removed
     */
    public String removeUrls(String text) {
        if (text == null) {
            return null;
        }
        if (StringUtils.isBlank(text)) {
            return text;
        }
        return text.replaceAll("https?://\\S+|www\\.\\S+", "");
    }

    /**
     * Performs a comprehensive cleanup of the input text by:
     * 1. Removing HTML tags
     * 2. Removing URLs
     * 3. Normalizing whitespace
     *
     * @param text the text to clean
     * @return the comprehensively cleaned text
     */
    public String cleanupText(String text) {
        if (text == null) {
            return null;
        }
        if (StringUtils.isBlank(text)) {
            return "";
        }

        String result = text;
        result = removeHtmlTags(result);
        result = removeUrls(result);
        result = normalizeWhitespace(result);

        return result;
    }
}
