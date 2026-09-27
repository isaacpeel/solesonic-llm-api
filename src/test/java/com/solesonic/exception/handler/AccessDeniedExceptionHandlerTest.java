package com.solesonic.exception.handler;

import com.solesonic.model.security.SecurityEventReason;
import com.solesonic.service.security.SecurityEventLogger;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.solesonic.model.security.SecurityEvent.AUTHORIZATION_DENIED;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * That a role gate on a controller method actually denies.
 * <p>
 * The gate itself is an annotation, and an annotation that throws into a catch-all advice returning
 * {@code 200} is indistinguishable from no gate at all. Both advices are registered here in the
 * order Spring would apply them, because the precedence <em>is</em> the behaviour under test:
 * {@link GeneralExceptionHandler} declares {@code @ExceptionHandler(RuntimeException.class)}, and
 * {@link AccessDeniedException} is a {@link RuntimeException}.
 */
@ExtendWith(MockitoExtension.class)
class AccessDeniedExceptionHandlerTest {

    @RestController
    static class DeniedController {

        @GetMapping("/documents/global")
        public String denied() {
            throw new AccessDeniedException("Access is denied");
        }

        @GetMapping("/documents/global/unauthenticated")
        public String unauthenticated() {
            throw new AuthenticationCredentialsNotFoundException("An Authentication object was not found");
        }
    }

    @Mock
    private SecurityEventLogger securityEventLogger;

    @Mock
    private ExceptionService exceptionService;

    private MockMvc mockMvc;

    @BeforeEach
    void beforeEach() {
        mockMvc = MockMvcBuilders.standaloneSetup(new DeniedController())
                .setControllerAdvice(new AccessDeniedExceptionHandler(securityEventLogger),
                        new GeneralExceptionHandler(exceptionService, securityEventLogger))
                .build();
    }

    @Test
    void aRoleDenialLeavesAsA403NotAChatShaped200() throws Exception {
        mockMvc.perform(get("/documents/global"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnauthenticatedCallerLeavesAsA401() throws Exception {
        mockMvc.perform(get("/documents/global/unauthenticated"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The filter chain's own {@code AccessDeniedHandler} never runs for a refusal thrown inside a
     * handler method, so without this the denial reaches no security log — and the fail2ban jails
     * read that log rather than the application one.
     */
    @Test
    void theDenialIsWrittenToTheSecurityLog() throws Exception {
        mockMvc.perform(get("/documents/global"));

        verify(securityEventLogger).log(eq(AUTHORIZATION_DENIED),
                any(HttpServletRequest.class),
                eq(SC_FORBIDDEN),
                eq(SecurityEventReason.INSUFFICIENT_AUTHORITY));
    }
}
