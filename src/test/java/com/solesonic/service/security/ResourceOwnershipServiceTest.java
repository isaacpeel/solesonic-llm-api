package com.solesonic.service.security;

import com.solesonic.model.security.SecurityEventReason;
import com.solesonic.scope.UserRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

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
}
