package com.solesonic.service.redis;

import com.solesonic.redis.service.RedisStreamService;
import com.solesonic.service.chat.ChatStreamAccessService;
import com.solesonic.service.chat.ChatStreamAccessService.ChatAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StreamResumeServiceTest {
    private static final UUID CHAT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    private static final String FIRST_FRAME = "1754062831000-0";
    private static final String MIDDLE_FRAME = "1754062831251-0";
    private static final String LAST_FRAME = "1754062831900-3";

    @Mock
    private RedisStreamService redisStreamService;

    @Mock
    private ChatStreamAccessService chatStreamAccessService;

    private StreamResumeService streamResumeService;

    @BeforeEach
    void setUp() {
        streamResumeService = new StreamResumeService(redisStreamService, chatStreamAccessService, 5L);

        when(chatStreamAccessService.forExistingChat(any(), any(), any())).thenReturn(ChatAccess.GRANTED);
        when(redisStreamService.getEarliestOffset(CHAT_ID, USER_ID)).thenReturn(Mono.just(FIRST_FRAME));
        when(redisStreamService.subscribe(any(), any(), any())).thenReturn(Flux.empty());
    }

    private void tailIs(String eventId, String type) {
        when(redisStreamService.tail(CHAT_ID, USER_ID))
                .thenReturn(Mono.just(new RedisStreamService.StreamTail(eventId, type)));
    }

    private ResponseEntity<Flux<ServerSentEvent<?>>> resume(String lastEventId) {
        return streamResumeService.resume(null, CHAT_ID, USER_ID, lastEventId);
    }

    @Test
    void replaysFromTheCursorOnALiveTurn() {
        tailIs(LAST_FRAME, "chunk");

        assertThat(resume(MIDDLE_FRAME).getStatusCode()).isEqualTo(HttpStatus.OK);

        verify(redisStreamService).subscribe(CHAT_ID, USER_ID, MIDDLE_FRAME);
    }

    @Test
    void replaysWholeTurnWhenTheCursorIsTheBeginning() {
        tailIs(LAST_FRAME, "done");

        assertThat(resume("0").getStatusCode()).isEqualTo(HttpStatus.OK);

        verify(redisStreamService).subscribe(CHAT_ID, USER_ID, "0");
    }

    @Test
    void replaysTailOfAFinishedTurn() {
        tailIs(LAST_FRAME, "done");

        assertThat(resume(MIDDLE_FRAME).getStatusCode()).isEqualTo(HttpStatus.OK);

        verify(redisStreamService).subscribe(CHAT_ID, USER_ID, MIDDLE_FRAME);
    }

    /**
     * The hang this endpoint exists to avoid: the client holds every frame of a finished turn, and
     * subscribing would wait on a stream nothing will write to again.
     */
    @Test
    void answersNoContentWhenTheClientAlreadyHasTheWholeFinishedTurn() {
        tailIs(LAST_FRAME, "done");

        assertThat(resume(LAST_FRAME).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        verify(redisStreamService, never()).subscribe(any(), any(), any());
    }

    @Test
    void answersGoneWhenNothingIsBuffered() {
        when(redisStreamService.tail(CHAT_ID, USER_ID)).thenReturn(Mono.empty());

        assertThat(resume(MIDDLE_FRAME).getStatusCode()).isEqualTo(HttpStatus.GONE);

        verify(redisStreamService, never()).subscribe(any(), any(), any());
    }

    /**
     * Replaying from a cursor older than the oldest surviving frame would drop content out of the
     * middle of the assistant's message. The client appends blindly, so that damage is silent.
     */
    @Test
    void answersGoneWhenTheCursorPredatesTheOldestRetainedFrame() {
        tailIs(LAST_FRAME, "chunk");

        assertThat(resume("1754062830000-0").getStatusCode()).isEqualTo(HttpStatus.GONE);

        verify(redisStreamService, never()).subscribe(any(), any(), any());
    }

    /**
     * A cursor we never issued means the client invented one — most likely by parsing a stream id
     * as an integer, which is the failure this rejects loudly rather than silently replaying from
     * the wrong place.
     */
    @Test
    void answersBadRequestOnACursorThatIsNotAStreamId() {
        tailIs(LAST_FRAME, "chunk");

        assertThat(resume("seven").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        verify(redisStreamService, never()).subscribe(any(), any(), any());
    }

    @Test
    void answersForbiddenForSomeoneElsesChat() {
        when(chatStreamAccessService.forExistingChat(any(), eq(CHAT_ID), eq(USER_ID)))
                .thenReturn(ChatAccess.FORBIDDEN);

        assertThat(resume(MIDDLE_FRAME).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        verify(redisStreamService, never()).tail(any(), any());
    }

    @Test
    void answersNotFoundForAnUnknownChat() {
        when(chatStreamAccessService.forExistingChat(any(), eq(CHAT_ID), eq(USER_ID)))
                .thenReturn(ChatAccess.NOT_FOUND);

        assertThat(resume(MIDDLE_FRAME).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        verify(redisStreamService, never()).tail(any(), any());
    }

    @Test
    void treatsAMissingCursorAsTheBeginning() {
        tailIs(LAST_FRAME, "chunk");

        assertThat(resume(null).getStatusCode()).isEqualTo(HttpStatus.OK);

        verify(redisStreamService).subscribe(CHAT_ID, USER_ID, "0");
    }
}
