package com.solesonic.service.image;

import com.solesonic.model.image.GeneratedImage;
import com.solesonic.model.image.GeneratedImageSummary;
import com.solesonic.repository.image.GeneratedImageRepository;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.user.UserProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeneratedImageServiceTest {

    private static final String CONTEXT_PATH = "/api";
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID OTHER_OWNER_ID = UUID.randomUUID();
    private static final PageRequest PAGE = PageRequest.of(0, 20);

    @Mock
    private GeneratedImageRepository generatedImageRepository;

    @Mock
    private UserRequestContext userRequestContext;

    @Mock
    private UserProfileService userProfileService;

    private GeneratedImageService generatedImageService;

    @BeforeEach
    void setUp() {
        generatedImageService = new GeneratedImageService(generatedImageRepository, userRequestContext, userProfileService, CONTEXT_PATH);
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

    @Test
    void theAdminListingNamesEachOwner() {
        GeneratedImageSummary first = summary(OWNER_ID);
        GeneratedImageSummary second = summary(OTHER_OWNER_ID);

        when(generatedImageRepository.findSummaryPage(PAGE)).thenReturn(new PageImpl<>(List.of(first, second), PAGE, 2));
        when(userProfileService.usernames(List.of(OWNER_ID, OTHER_OWNER_ID))).thenReturn(Map.of(OWNER_ID, "sam"));

        List<GeneratedImageSummary> summaries = generatedImageService.listAll(null, PAGE).getContent();

        assertThat(summaries).extracting(GeneratedImageSummary::username).containsExactly("sam", null);
        assertThat(summaries).extracting(GeneratedImageSummary::imageUrl)
                .containsExactly(CONTEXT_PATH + "/images/" + first.imageId(), CONTEXT_PATH + "/images/" + second.imageId());
    }

    @Test
    void theOwnersListingNeverLooksUpUsernames() {
        when(generatedImageRepository.findSummaryPageByUserId(OWNER_ID, PAGE))
                .thenReturn(new PageImpl<>(List.of(summary(OWNER_ID)), PAGE, 1));

        assertThat(generatedImageService.list(OWNER_ID, PAGE).getContent())
                .extracting(GeneratedImageSummary::username)
                .containsOnlyNulls();
        verifyNoInteractions(userProfileService);
    }

    @Test
    void theAdminRenameNamesTheOwner() {
        GeneratedImage generatedImage = new GeneratedImage();
        generatedImage.setId(UUID.randomUUID());
        generatedImage.setUserId(OWNER_ID);

        when(generatedImageRepository.findById(generatedImage.getId())).thenReturn(Optional.of(generatedImage));
        when(generatedImageRepository.save(generatedImage)).thenReturn(generatedImage);
        when(userProfileService.usernames(List.of(OWNER_ID))).thenReturn(Map.of(OWNER_ID, "sam"));

        GeneratedImageSummary renamed = generatedImageService.rename(generatedImage.getId(), " Lighthouse ");

        assertThat(renamed.name()).isEqualTo("Lighthouse");
        assertThat(renamed.username()).isEqualTo("sam");
    }

    private static GeneratedImageSummary summary(UUID ownerId) {
        return new GeneratedImageSummary(UUID.randomUUID(), ownerId, null, null, null, "a lighthouse",
                "flux", 42L, 1024, 1024, 20, 8.5, 2048L, ZonedDateTime.now(), List.of());
    }
}
