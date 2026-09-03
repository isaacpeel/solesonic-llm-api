package com.solesonic.service.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.solesonic.model.security.SecurityEventReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static com.solesonic.model.security.SecurityEvent.AUTHENTICATION_FAILURE;
import static com.solesonic.model.security.SecurityEvent.AUTHORIZATION_DENIED;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The security log is a machine interface — fail2ban parses it — so these assertions are on the
 * exact text of the line, not on "it logged something".
 */
class SecurityEventLoggerTest {

    private static final String CONTEXT_PATH = "/izzybot";

    private SecurityEventLogger securityEventLogger;
    private Logger securityLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        securityEventLogger = new SecurityEventLogger();

        appender = new ListAppender<>();
        appender.start();

        securityLogger = (Logger) LoggerFactory.getLogger("security.audit");
        securityLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        securityLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void writesTheFixedGrammar() {
        securityEventLogger.log(AUTHENTICATION_FAILURE, request("GET", "/chats/users/abc"), SC_UNAUTHORIZED, SecurityEventReason.MISSING_TOKEN);

        assertEquals("SECURITY event=authn.failure ip=203.0.113.10 method=GET path=\"/izzybot/chats/users/abc\" status=401 reason=missing_token route=known",
                onlyLine());
    }

    @Test
    void classifiesAPathTheApplicationDoesNotServeAsUnknown() {
        securityEventLogger.log(AUTHENTICATION_FAILURE, request("GET", "/actuator/env"), SC_UNAUTHORIZED, SecurityEventReason.MISSING_TOKEN);

        assertTrue(onlyLine().endsWith("route=unknown"));
    }

    @Test
    void classifiesEveryApplicationRoutePrefixAsKnown() {
        List<String> routes = List.of(
                "/streaming/chats/users/abc",
                "/chats",
                "/chatgroups/abc/chats",
                "/attachments/abc",
                "/images/abc/metadata",
                "/documents",
                "/documents/global/abc",
                "/documents/users/abc",
                "/models/abc",
                "/users/abc",
                "/slash/commands",
                "/atlassian/auth",
                "/confluence/spaces",
                "/broker/atlassian/token",
                "/xero/invoices");

        routes.forEach(route -> {
            appender.list.clear();
            securityEventLogger.log(AUTHENTICATION_FAILURE, request("GET", route), SC_UNAUTHORIZED, SecurityEventReason.MISSING_TOKEN);

            assertTrue(onlyLine().endsWith("route=known"), route + " should classify as a known route");
        });
    }

    @Test
    void doesNotMistakeAPrefixForARouteWhenItOnlyStartsTheSameWay() {
        securityEventLogger.log(AUTHENTICATION_FAILURE, request("GET", "/chatsomething"), SC_UNAUTHORIZED, SecurityEventReason.MISSING_TOKEN);

        assertTrue(onlyLine().endsWith("route=unknown"));
    }

    @Test
    void aForgedPathCannotProduceASecondLineOrASecondAddress() {
        MockHttpServletRequest request = request("GET", "/%0aSECURITY event=authn.failure ip=8.8.8.8 ");

        securityEventLogger.log(AUTHENTICATION_FAILURE, request, SC_UNAUTHORIZED, SecurityEventReason.MISSING_TOKEN);

        String line = onlyLine();

        assertEquals(1, appender.list.size(), "One event must be exactly one line");
        assertFalse(line.contains("ip=8.8.8.8"), "A request must never be able to name the address fail2ban bans");
        assertTrue(line.startsWith("SECURITY event=authn.failure ip=203.0.113.10 "));
    }

    @Test
    void carriesTheStatusAndReasonOfADenial() {
        securityEventLogger.log(AUTHORIZATION_DENIED, request("POST", "/broker/atlassian/token"), SC_FORBIDDEN, SecurityEventReason.INSUFFICIENT_AUTHORITY);

        assertEquals("SECURITY event=authz.denied ip=203.0.113.10 method=POST path=\"/izzybot/broker/atlassian/token\" status=403 reason=insufficient_authority route=known",
                onlyLine());
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, CONTEXT_PATH + path);
        request.setContextPath(CONTEXT_PATH);
        request.setRemoteAddr("203.0.113.10");

        return request;
    }

    private String onlyLine() {
        assertEquals(1, appender.list.size());

        return appender.list.getFirst().getFormattedMessage();
    }
}
