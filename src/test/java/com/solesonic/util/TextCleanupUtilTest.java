package com.solesonic.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextCleanupUtilTest {

    private TextCleanupUtil textCleanupUtil;

    @BeforeEach
    void setUp() {
        textCleanupUtil = new TextCleanupUtil();
    }

    @Test
    void removeSpecialCharacters_shouldRemoveAllSpecialCharacters() {
        // Given
        String input = "Hello, World! This is a test. 123-456-7890";
        
        // When
        String result = textCleanupUtil.removeSpecialCharacters(input);
        
        // Then
        assertEquals("Hello World This is a test 1234567890", result);
    }
    
    @Test
    void removeSpecialCharacters_shouldHandleNullAndEmptyStrings() {
        // Given
        String nullInput = null;
        String emptyInput = "";
        String blankInput = "   ";
        
        // When & Then
        assertEquals(nullInput, textCleanupUtil.removeSpecialCharacters(nullInput));
        assertEquals(emptyInput, textCleanupUtil.removeSpecialCharacters(emptyInput));
        assertEquals(blankInput, textCleanupUtil.removeSpecialCharacters(blankInput));
    }

    @Test
    void normalizeWhitespace_shouldNormalizeWhitespace() {
        // Given
        String input = "  Hello   World  \t\n  This is a test  ";
        
        // When
        String result = textCleanupUtil.normalizeWhitespace(input);
        
        // Then
        assertEquals("Hello World This is a test", result);
    }
    
    @Test
    void normalizeWhitespace_shouldHandleNullAndEmptyStrings() {
        // Given
        String nullInput = null;
        String emptyInput = "";
        String blankInput = "   ";
        
        // When & Then
        assertEquals(nullInput, textCleanupUtil.normalizeWhitespace(nullInput));
        assertEquals(emptyInput, textCleanupUtil.normalizeWhitespace(emptyInput));
        assertEquals("", textCleanupUtil.normalizeWhitespace(blankInput));
    }

    @Test
    void removeHtmlTags_shouldRemoveAllHtmlTags() {
        // Given
        String input = "<p>Hello <b>World</b>!</p> <div>This is a <span>test</span>.</div>";
        
        // When
        String result = textCleanupUtil.removeHtmlTags(input);
        
        // Then
        assertEquals("Hello World! This is a test.", result);
    }
    
    @Test
    void removeHtmlTags_shouldHandleNullAndEmptyStrings() {
        // Given
        String nullInput = null;
        String emptyInput = "";
        String blankInput = "   ";
        
        // When & Then
        assertEquals(nullInput, textCleanupUtil.removeHtmlTags(nullInput));
        assertEquals(emptyInput, textCleanupUtil.removeHtmlTags(emptyInput));
        assertEquals(blankInput, textCleanupUtil.removeHtmlTags(blankInput));
    }

    @Test
    void removeUrls_shouldRemoveAllUrls() {
        // Given
        String input = "Visit https://example.com or www.example.org for more information.";
        
        // When
        String result = textCleanupUtil.removeUrls(input);
        
        // Then
        assertEquals("Visit  or  for more information.", result);
    }
    
    @Test
    void removeUrls_shouldHandleNullAndEmptyStrings() {
        // Given
        String nullInput = null;
        String emptyInput = "";
        String blankInput = "   ";
        
        // When & Then
        assertEquals(nullInput, textCleanupUtil.removeUrls(nullInput));
        assertEquals(emptyInput, textCleanupUtil.removeUrls(emptyInput));
        assertEquals(blankInput, textCleanupUtil.removeUrls(blankInput));
    }

    @Test
    void cleanupText_shouldPerformComprehensiveCleanup() {
        // Given
        String input = "  <p>Visit https://example.com</p> or <b>www.example.org</b> for more   information.  ";
        
        // When
        String result = textCleanupUtil.cleanupText(input);
        
        // Then
        assertEquals("Visit or for more information.", result);
    }
    
    @Test
    void cleanupText_shouldHandleNullAndEmptyStrings() {
        // Given
        String nullInput = null;
        String emptyInput = "";
        String blankInput = "   ";
        
        // When & Then
        assertEquals(nullInput, textCleanupUtil.cleanupText(nullInput));
        assertEquals(emptyInput, textCleanupUtil.cleanupText(emptyInput));
        assertEquals("", textCleanupUtil.cleanupText(blankInput));
    }
}