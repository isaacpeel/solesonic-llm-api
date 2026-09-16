package com.solesonic.api.chat;

import com.solesonic.service.chat.ChatStreamAccessService;
import com.solesonic.service.chat.ChatStreamAccessService.ChatAccess;
import com.solesonic.service.chat.events.ElicitationService;
import com.solesonic.service.redis.RedisStreamingChatService;
import com.solesonic.service.redis.RedisStreamingChatService.CancelOutcome;
import com.solesonic.service.redis.StreamResumeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the status-code contract of {@link StreamingChatController#cancel}: ownership is checked the
 * same way {@code update} and {@code resume} already check it, and the outcome from
 * {@link RedisStreamingChatService#cancel} maps to a status without ever blocking on it.
 */
@ExtendWith(MockitoExtension.class)
class StreamingChatControllerTest {

    @Mock
    private RedisStreamingChatService streamingChatService;

    @Mock
    private ElicitationService elicitationService;

    @Mock
    private StreamResumeService streamResumeService;

    @Mock
    private ChatStreamAccessService chatStreamAccessService;

    @Mock
    private Authentication authentication;

    private StreamingChatController streamingChatController;

    private UUID chatId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        chatId = UUID.randomUUID();
        userId = UUID.randomUUID();

        streamingChatController = new StreamingChatController(streamingChatService, elicitationService,
                streamResumeService, chatStreamAccessService);
    }

    @Test
    void deniesCancellingAChatTheCallerDoesNotOwn() {
        when(chatStreamAccessService.forExistingChat(authentication, chatId, userId)).thenReturn(ChatAccess.FORBIDDEN);

        StepVerifier.create(streamingChatController.cancel(chatId, userId, authentication))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN))
                .verifyComplete();

        verify(streamingChatService, never()).cancel(any(), any());
    }

    @Test
    void propagatesNotFoundForAnUnknownChat() {
        when(chatStreamAccessService.forExistingChat(authentication, chatId, userId)).thenReturn(ChatAccess.NOT_FOUND);

        StepVerifier.create(streamingChatController.cancel(chatId, userId, authentication))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .verifyComplete();

        verify(streamingChatService, never()).cancel(any(), any());
    }

    @Test
    void acceptsCancellingATurnInFlight() {
        when(chatStreamAccessService.forExistingChat(authentication, chatId, userId)).thenReturn(ChatAccess.GRANTED);
        when(streamingChatService.cancel(chatId, userId)).thenReturn(Mono.just(CancelOutcome.CANCEL_REQUESTED));

        StepVerifier.create(streamingChatController.cancel(chatId, userId, authentication))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED))
                .verifyComplete();
    }

    @Test
    void answersNoContentWhenThereIsNothingToCancel() {
        when(chatStreamAccessService.forExistingChat(authentication, chatId, userId)).thenReturn(ChatAccess.GRANTED);
        when(streamingChatService.cancel(chatId, userId)).thenReturn(Mono.just(CancelOutcome.NOTHING_TO_CANCEL));

        StepVerifier.create(streamingChatController.cancel(chatId, userId, authentication))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT))
                .verifyComplete();
    }
}
