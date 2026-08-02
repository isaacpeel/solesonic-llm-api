package com.solesonic.util.logging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedactorTest {

    @Test
    void redactsTheValueRatherThanTheParameterName() {
        String redacted = Redactor.redactQuery("code=abc123");

        assertEquals("code=*****", redacted);
        assertFalse(redacted.contains("abc123"), "The OAuth authorization code must not reach a log file");
    }

    @Test
    void matchesParameterNamesExactlyRatherThanBySubstring() {
        assertEquals("qrcode=abc123", Redactor.redactQuery("qrcode=abc123"));
        assertEquals("chatCode=abc123", Redactor.redactQuery("chatCode=abc123"));
    }

    @Test
    void redactsEveryDenylistedParameterAndKeepsTheRest() {
        String redacted = Redactor.redactQuery("page=2&state=xyz&access_token=secret&size=10");

        assertTrue(redacted.contains("page=2"));
        assertTrue(redacted.contains("size=10"));
        assertTrue(redacted.contains("state=*****"));
        assertTrue(redacted.contains("access_token=*****"));
        assertFalse(redacted.contains("xyz"));
        assertFalse(redacted.contains("secret"));
    }

    @Test
    void isCaseInsensitiveOnParameterNames() {
        assertEquals("Code=*****", Redactor.redactQuery("Code=abc123"));
    }

    @Test
    void keepsAValuelessParameter() {
        assertEquals("prompt", Redactor.redactQuery("prompt"));
    }

    @Test
    void passesThroughAnAbsentQueryString() {
        assertNull(Redactor.redactQuery(null));
        assertEquals("", Redactor.redactQuery(""));
    }

    @Test
    void sanitizedPathCannotForgeALogLine() {
        String forged = "/probe\nSECURITY event=authn.failure ip=8.8.8.8 method=GET";

        String sanitized = Redactor.sanitizePath(forged);

        assertFalse(sanitized.contains("\n"), "A newline would let a request write its own log line");
        assertFalse(sanitized.contains("="), "Without '=' no field of the grammar can be forged");
        assertFalse(sanitized.contains(" "));
    }

    @Test
    void sanitizedPathDropsQuotesAndControlCharacters() {
        String sanitized = Redactor.sanitizePath("/chats/\"quoted\"\r\n\t");

        assertEquals("/chats/quoted", sanitized);
    }

    @Test
    void sanitizedPathIsTruncated() {
        String sanitized = Redactor.sanitizePath("/" + "a".repeat(500));

        assertEquals(120, sanitized.length());
    }

    @Test
    void sanitizedPathKeepsAnOrdinaryRoute() {
        assertEquals("/izzybot/chats/users/00000000-0000-0000-0000-000000000000",
                Redactor.sanitizePath("/izzybot/chats/users/00000000-0000-0000-0000-000000000000"));
    }

    @Test
    void absentPathBecomesTheAbsentMarker() {
        assertEquals("-", Redactor.sanitizePath(null));
        assertEquals("-", Redactor.sanitizePath(""));
    }

    @Test
    void onlyKnownShapedMethodsSurvive() {
        assertEquals("GET", Redactor.sanitizeMethod("GET"));
        assertEquals("DELETE", Redactor.sanitizeMethod("DELETE"));
        assertEquals("-", Redactor.sanitizeMethod("GET\nSECURITY"));
        assertEquals("-", Redactor.sanitizeMethod(null));
    }
}
