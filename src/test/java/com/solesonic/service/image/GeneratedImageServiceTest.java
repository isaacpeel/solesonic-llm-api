package com.solesonic.service.image;

import com.solesonic.repository.image.GeneratedImageRepository;
import com.solesonic.scope.UserRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeneratedImageServiceTest {

    private static final String CONTEXT_PATH = "/api";

    @Mock
    private GeneratedImageRepository generatedImageRepository;

    @Mock
    private UserRequestContext userRequestContext;

    private GeneratedImageService generatedImageService;

    @BeforeEach
    void setUp() {
        generatedImageService = new GeneratedImageService(generatedImageRepository, userRequestContext, CONTEXT_PATH);
    }

    /**
     * The message-scoped counterpart to deleting a whole chat's images: no vector-store or
     * {@code ingested_document} dependents to sweep first, so this is a plain bulk delete.
     */
    @Test
    void deleteForChatMessageDeletesByChatMessageId() {
        UUID chatMessageId = UUID.randomUUID();

        when(generatedImageRepository.deleteByChatMessageId(chatMessageId)).thenReturn(2);

        generatedImageService.deleteForChatMessage(chatMessageId);

        verify(generatedImageRepository).deleteByChatMessageId(chatMessageId);
    }

    @Test
    void deleteForChatMessageIsANoOpWhenNothingMatches() {
        UUID chatMessageId = UUID.randomUUID();

        when(generatedImageRepository.deleteByChatMessageId(chatMessageId)).thenReturn(0);

        generatedImageService.deleteForChatMessage(chatMessageId);

        verify(generatedImageRepository).deleteByChatMessageId(chatMessageId);
    }
}
