package com.solesonic.service.security;

import com.solesonic.model.security.SecurityEventReason;
import com.solesonic.scope.UserRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

import static com.solesonic.model.security.SecurityEvent.AUTHORIZATION_DENIED;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ResourceOwnershipServiceTest {
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();

    private final UserRequestContext userRequestContext = new UserRequestContext();

    @Mock
    private SecurityEventLogger securityEventLogger;

    private ResourceOwnershipService resourceOwnershipService;

    @BeforeEach
    void setUp() {
        resourceOwnershipService = new ResourceOwnershipService(userRequestContext, securityEventLogger);
    }

    @Test
    void grantsAccessWhenThePathUserIsTheCaller() {
        userRequestContext.setUserId(USER_ID);

        assertThat(resourceOwnershipService.isOwner(USER_ID, new MockHttpServletRequest())).isTrue();

        verifyNoInteractions(securityEventLogger);
    }

    @Test
    void deniesAccessAndLogsWhenThePathUserIsNotTheCaller() {
        userRequestContext.setUserId(USER_ID);
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(resourceOwnershipService.isOwner(OTHER_USER_ID, request)).isFalse();

        verify(securityEventLogger).log(AUTHORIZATION_DENIED, request, SC_FORBIDDEN, SecurityEventReason.WRONG_SUBJECT);
    }

    @Test
    void deniesAccessWhenNoCallerHasBeenResolved() {
        assertThat(resourceOwnershipService.isOwner(USER_ID, new MockHttpServletRequest())).isFalse();
    }

    /**
     * The authority is checked before ownership, so an administrator on another user's path is
     * neither refused nor logged as a denial.
     */
    @Test
    void grantsAnotherUsersPathToAHolderOfTheAuthorityWithoutLoggingADenial() {
        userRequestContext.setUserId(USER_ID);
        authenticated("GROUP_izzy-bot-admin");

        assertThat(resourceOwnershipService.isOwnerOrHasAuthority(OTHER_USER_ID, "GROUP_izzy-bot-admin", new MockHttpServletRequest()))
                .isTrue();

        verifyNoInteractions(securityEventLogger);
    }

    @Test
    void fallsBackToOwnershipWithoutTheAuthority() {
        userRequestContext.setUserId(USER_ID);
        authenticated("ROLE_izzy-bot-admin");
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(resourceOwnershipService.isOwnerOrHasAuthority(USER_ID, "GROUP_izzy-bot-admin", request)).isTrue();
        assertThat(resourceOwnershipService.isOwnerOrHasAuthority(OTHER_USER_ID, "GROUP_izzy-bot-admin", request)).isFalse();

        verify(securityEventLogger).log(AUTHORIZATION_DENIED, request, SC_FORBIDDEN, SecurityEventReason.WRONG_SUBJECT);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticated(String authority) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("caller", null, authority));
    }
}
