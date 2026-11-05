package com.solesonic.exception.handler;

import com.solesonic.exception.session.SessionException;
import com.solesonic.model.session.SessionErrorCode;
import com.solesonic.model.session.SessionErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SessionExceptionHandlerTest {

    private final SessionExceptionHandler handler = new SessionExceptionHandler();

    @Test
    void mapsTheConversationCodesToTheirStatuses() {
        assertThat(statusOf(SessionErrorCode.TOO_MANY_PARTICIPANTS)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(SessionErrorCode.NOT_SESSION_OWNER)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf(SessionErrorCode.CHAT_STREAMING)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(SessionErrorCode.COMMAND_NOT_ALLOWED)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statusOf(SessionErrorCode.SESSION_LIMIT_REACHED)).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void carriesTheUsernamesAParticipantNotFoundNamed() {
        SessionException exception = new SessionException(SessionErrorCode.PARTICIPANT_NOT_FOUND,
                "Some users were not found", List.of("ghost", "nobody"));

        ResponseEntity<SessionErrorResponse> response = handler.handleSession(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().usernames()).containsExactly("ghost", "nobody");
    }

    @Test
    void leavesUsernamesNullWhenTheExceptionHasNone() {
        ResponseEntity<SessionErrorResponse> response = handler.handleSession(
                new SessionException(SessionErrorCode.SESSION_NOT_FOUND, "Shared session not found"));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().usernames()).isNull();
    }

    private HttpStatus statusOf(SessionErrorCode code) {
        return HttpStatus.valueOf(handler.handleSession(new SessionException(code, "x")).getStatusCode().value());
    }
}
