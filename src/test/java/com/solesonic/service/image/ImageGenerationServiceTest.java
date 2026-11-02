package com.solesonic.service.image;

import com.solesonic.exception.image.ImageGenerationException;
import com.solesonic.exception.llm.LlmKeyMissingException;
import com.solesonic.model.chat.attachment.ChatAttachment;
import com.solesonic.model.image.GeneratedImageBytes;
import com.solesonic.model.image.GeneratedImageSummary;
import com.solesonic.model.image.ImageGenerationFailure;
import com.solesonic.model.image.ImageGenerationMetadata;
import com.solesonic.model.llm.LlmApiKey;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import com.solesonic.service.llm.UserLlmKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static com.solesonic.model.image.ImageGenerationErrorCode.GENERATION_TIMEOUT;
import static com.solesonic.model.image.ImageGenerationErrorCode.INVALID_PROMPT;
import static com.solesonic.model.image.ImageGenerationErrorCode.INVALID_REFERENCE_IMAGE;
import static com.solesonic.model.image.ImageGenerationErrorCode.RATE_LIMITED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImageGenerationServiceTest {

    private static final byte[] PNG_BYTES = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final LlmApiKey LLM_API_KEY = new LlmApiKey("sk-user-0000000001");
    private static final int MAX_REFERENCE_IMAGES = 16;

    @Mock
    private LiteLlmImageClient liteLlmImageClient;

    @Mock
    private GeneratedImageService generatedImageService;

    @Mock
    private ChatAttachmentService chatAttachmentService;

    @Mock
    private UserLlmKeyService userLlmKeyService;

    private ImageGenerationService imageGenerationService;
    private UUID userId;

    @BeforeEach
    void setUp() {
        imageGenerationService = service(1, Duration.ofSeconds(1), Duration.ofMinutes(10));

        userId = UUID.randomUUID();

        when(userLlmKeyService.requireKey(userId)).thenReturn(LLM_API_KEY);
        when(liteLlmImageClient.generate(anyString(), any())).thenReturn(bytes("solesonic-image (izzy-bot)"));
        when(liteLlmImageClient.edit(anyString(), anyList(), any())).thenReturn(bytes("solesonic-image-edit (izzy-bot)"));
        when(generatedImageService.store(any(), any(), any(), any(), any(), any(), any())).thenReturn(summary());
    }

    @Test
    void generate_withoutReferencesCallsGenerationsOnTheUsersKey() {
        imageGenerationService.generate("  a lighthouse  ", List.of(), userId);

        verify(liteLlmImageClient).generate("a lighthouse", LLM_API_KEY);
        verify(liteLlmImageClient, never()).edit(anyString(), anyList(), any());
        verify(generatedImageService).store(eq(userId), isNull(), eq("a lighthouse"), eq(PNG_BYTES), eq("image/png"),
                any(), eq(List.of()));
    }

    @Test
    void generate_withReferencesCallsEditsWithTheAttachmentsInOrder() {
        ChatAttachment first = attachment();
        ChatAttachment second = attachment();
        when(chatAttachmentService.attachments(eq(userId), anySet())).thenReturn(List.of(second, first));

        imageGenerationService.generate("put us on a beach", List.of(first.getId(), second.getId()), userId);

        verify(liteLlmImageClient).edit("put us on a beach", List.of(first, second), LLM_API_KEY);
        verify(generatedImageService).store(eq(userId), isNull(), eq("put us on a beach"), any(), any(), any(),
                eq(List.of(first.getId(), second.getId())));
    }

    @Test
    void generate_storesTheModelSizeAndMeasuredTimeAndNothingTheServiceDoesNotReturn() {
        imageGenerationService.generate("a lighthouse", List.of(), userId);

        ArgumentCaptor<ImageGenerationMetadata> metadataCaptor = ArgumentCaptor.forClass(ImageGenerationMetadata.class);
        verify(generatedImageService).store(any(), any(), any(), any(), any(), metadataCaptor.capture(), any());

        ImageGenerationMetadata imageGenerationMetadata = metadataCaptor.getValue();

        assertThat(imageGenerationMetadata.model()).isEqualTo("solesonic-image (izzy-bot)");
        assertThat(imageGenerationMetadata.width()).isEqualTo(1024);
        assertThat(imageGenerationMetadata.height()).isEqualTo(768);
        assertThat(imageGenerationMetadata.seed()).isNull();
        assertThat(imageGenerationMetadata.steps()).isNull();
        assertThat(imageGenerationMetadata.elapsedSeconds()).isNotNull().isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void generate_aBlankPromptIsRefusedBeforeAnyCall() {
        assertThatThrownBy(() -> imageGenerationService.generate("   ", List.of(), userId))
                .isInstanceOfSatisfying(ImageGenerationException.class, imageGenerationException ->
                        assertThat(imageGenerationException.getErrorCode()).isEqualTo(INVALID_PROMPT));

        verifyNoInteractions(liteLlmImageClient);
    }

    /**
     * The same refusal chat gives a keyless user, ahead of admission so it never holds a GPU slot.
     */
    @Test
    void generate_forAUserWithNoKeyIsRefusedBeforeAdmission() {
        when(userLlmKeyService.requireKey(userId)).thenThrow(new LlmKeyMissingException());

        assertThatThrownBy(() -> imageGenerationService.generate("p", List.of(), userId))
                .isInstanceOf(LlmKeyMissingException.class);

        verifyNoInteractions(liteLlmImageClient);
    }

    @Test
    void generate_withAReferenceTheCallerCannotUseIsRefusedBeforeTheCall() {
        when(chatAttachmentService.attachments(eq(userId), anySet())).thenReturn(List.of());

        assertThatThrownBy(() -> imageGenerationService.generate("p", List.of(UUID.randomUUID()), userId))
                .isInstanceOfSatisfying(ImageGenerationException.class, imageGenerationException ->
                        assertThat(imageGenerationException.getErrorCode()).isEqualTo(INVALID_REFERENCE_IMAGE));

        verifyNoInteractions(liteLlmImageClient);
    }

    @Test
    void generate_withMoreReferencesThanTheServiceTakesIsRefusedBeforeTheCall() {
        List<UUID> tooMany = IntStream.rangeClosed(0, MAX_REFERENCE_IMAGES).mapToObj(_ -> UUID.randomUUID()).toList();

        assertThatThrownBy(() -> imageGenerationService.generate("p", tooMany, userId))
                .isInstanceOfSatisfying(ImageGenerationException.class, imageGenerationException -> {
                    assertThat(imageGenerationException.getErrorCode()).isEqualTo(INVALID_REFERENCE_IMAGE);
                    assertThat(imageGenerationException.getMessage()).contains("At most 16");
                });

        verifyNoInteractions(liteLlmImageClient, chatAttachmentService);
    }

    @Test
    void generate_withSixteenReferencesIsAccepted() {
        List<ChatAttachment> sixteen = IntStream.range(0, MAX_REFERENCE_IMAGES).mapToObj(_ -> attachment()).toList();
        when(chatAttachmentService.attachments(eq(userId), anySet())).thenReturn(sixteen);

        imageGenerationService.generate("p", sixteen.stream().map(ChatAttachment::getId).toList(), userId);

        verify(liteLlmImageClient).edit("p", sixteen, LLM_API_KEY);
    }

    @Test
    void generate_pastTheAdmissionBoundIsToldToRetry() throws InterruptedException {
        ImageGenerationService oneAtATime = service(1, Duration.ofMillis(50), Duration.ofMinutes(10));
        CountDownLatch inFlight = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        when(liteLlmImageClient.generate(eq("first"), any())).thenAnswer(_ -> {
            inFlight.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();

            return bytes("m");
        });

        Thread first = Thread.ofVirtual().start(() -> oneAtATime.generate("first", List.of(), userId));

        try {
            assertThat(inFlight.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> oneAtATime.generate("second", List.of(), userId))
                    .isInstanceOfSatisfying(ImageGenerationException.class, imageGenerationException ->
                            assertThat(imageGenerationException.getErrorCode()).isEqualTo(RATE_LIMITED));
        } finally {
            release.countDown();
            first.join();
        }
    }

    @Test
    void generate_releasesItsSlotWhenTheCallFails() {
        when(liteLlmImageClient.generate(eq("fails"), any()))
                .thenThrow(new ImageGenerationException(INVALID_PROMPT, "no"));

        assertThatThrownBy(() -> imageGenerationService.generate("fails", List.of(), userId))
                .isInstanceOf(ImageGenerationException.class);

        imageGenerationService.generate("succeeds", List.of(), userId);

        verify(liteLlmImageClient).generate("succeeds", LLM_API_KEY);
    }

    /**
     * Chat is lenient: an id it cannot use is skipped, and the turn goes on without it.
     */
    @Test
    void generateInChat_skipsUnusableReferencesAndStoresAgainstTheChat() {
        UUID chatId = UUID.randomUUID();
        ChatAttachment usable = attachment();
        when(chatAttachmentService.attachments(eq(userId), anySet())).thenReturn(List.of(usable));

        imageGenerationService.generateInChat("p", List.of(UUID.randomUUID(), usable.getId()), userId, chatId);

        verify(liteLlmImageClient).edit("p", List.of(usable), LLM_API_KEY);
        verify(generatedImageService).store(eq(userId), eq(chatId), eq("p"), any(), any(), any(), eq(List.of(usable.getId())));
    }

    @Test
    void generateInChat_withNoUsableReferenceGeneratesFromThePromptAlone() {
        when(chatAttachmentService.attachments(eq(userId), anySet())).thenReturn(List.of());

        imageGenerationService.generateInChat("p", List.of(UUID.randomUUID()), userId, UUID.randomUUID());

        verify(liteLlmImageClient).generate("p", LLM_API_KEY);
    }

    @Test
    void generateInChat_sendsAtMostTheServicesLimit() {
        List<ChatAttachment> seventeen = IntStream.rangeClosed(0, MAX_REFERENCE_IMAGES).mapToObj(_ -> attachment()).toList();
        when(chatAttachmentService.attachments(eq(userId), anySet())).thenReturn(seventeen);

        imageGenerationService.generateInChat("p", seventeen.stream().map(ChatAttachment::getId).toList(), userId,
                UUID.randomUUID());

        verify(liteLlmImageClient).edit("p", seventeen.subList(0, MAX_REFERENCE_IMAGES), LLM_API_KEY);
    }

    /**
     * A user with no key is refused on the request thread, before the response commits, so the
     * caller gets the same 403 chat gives rather than a 200 stream carrying an error frame.
     */
    @SuppressWarnings("all")
    @Test
    void stream_forAUserWithNoKeyThrowsBeforeTheStreamStarts() {
        when(userLlmKeyService.requireKey(userId)).thenThrow(new LlmKeyMissingException());

        assertThatThrownBy(() -> imageGenerationService.stream("p", List.of(), userId))
                .isInstanceOf(LlmKeyMissingException.class);
    }

    @Test
    void stream_emitsOnlyTheTerminalFrame() {
        GeneratedImageSummary generatedImageSummary = summary();
        when(generatedImageService.store(any(), any(), any(), any(), any(), any(), any())).thenReturn(generatedImageSummary);

        StepVerifier.create(imageGenerationService.stream("a lighthouse", List.of(), userId))
                .expectNext(generatedImageSummary)
                .verifyComplete();
    }

    @Test
    void stream_reportsAFailureAsAnErrorFrame() {
        when(liteLlmImageClient.generate(anyString(), any())).thenThrow(new ImageGenerationException(RATE_LIMITED, "busy"));

        StepVerifier.create(imageGenerationService.stream("p", List.of(), userId))
                .assertNext(event -> assertThat(event).isInstanceOfSatisfying(ImageGenerationFailure.class, failure ->
                        assertThat(failure.code()).isEqualTo(RATE_LIMITED)))
                .verifyComplete();
    }

    /**
     * Nothing reports progress any more, so the deadline is absolute. A call that never returns still
     * ends the stream rather than leaving the client with a spinner.
     */
    @Test
    void stream_endsWithATimeoutWhenTheCallOutlivesTheDeadline() {
        ImageGenerationService impatient = service(1, Duration.ofMillis(10), Duration.ofMillis(10));
        CountDownLatch release = new CountDownLatch(1);

        when(liteLlmImageClient.generate(anyString(), any())).thenAnswer(_ -> {
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();

            return bytes("m");
        });

        try {
            StepVerifier.withVirtualTime(() -> impatient.stream("p", List.of(), userId))
                    .expectSubscription()
                    .thenAwait(Duration.ofMinutes(5))
                    .assertNext(event -> assertThat(event).isInstanceOfSatisfying(ImageGenerationFailure.class,
                            failure -> assertThat(failure.code()).isEqualTo(GENERATION_TIMEOUT)))
                    .verifyComplete();
        } finally {
            release.countDown();
        }
    }

    private ImageGenerationService service(int maxConcurrent, Duration admissionTimeout, Duration readTimeout) {
        return new ImageGenerationService(liteLlmImageClient, generatedImageService,
                new ReferenceImageResolver(chatAttachmentService), userLlmKeyService,
                maxConcurrent, admissionTimeout, readTimeout, MAX_REFERENCE_IMAGES);
    }

    private ChatAttachment attachment() {
        ChatAttachment chatAttachment = new ChatAttachment();
        chatAttachment.setId(UUID.randomUUID());
        chatAttachment.setUserId(userId);
        chatAttachment.setContentType("image/png");
        chatAttachment.setFileData(PNG_BYTES);

        return chatAttachment;
    }

    private static GeneratedImageBytes bytes(String model) {
        return new GeneratedImageBytes(PNG_BYTES, "image/png", 1024, 768, model);
    }

    private GeneratedImageSummary summary() {
        return new GeneratedImageSummary(UUID.randomUUID(), userId, null, "/images/x", null, "p",
                "solesonic-image (izzy-bot)", null, 1024, 768, null, 1.0, PNG_BYTES.length, ZonedDateTime.now(), List.of());
    }
}
