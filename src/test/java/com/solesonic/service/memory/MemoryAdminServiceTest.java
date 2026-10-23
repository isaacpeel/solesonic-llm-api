package com.solesonic.service.memory;

import com.solesonic.model.memory.ChatMemoryReflection;
import com.solesonic.model.memory.MemoryKind;
import com.solesonic.model.memory.MemoryOrigin;
import com.solesonic.model.memory.MemoryRetrievalMode;
import com.solesonic.model.memory.MemoryStatus;
import com.solesonic.model.memory.MemorySummary;
import com.solesonic.model.memory.ReflectionStatus;
import com.solesonic.model.memory.ReflectionSummary;
import com.solesonic.model.memory.UserMemory;
import com.solesonic.model.memory.UserMemoryEvidence;
import com.solesonic.model.user.UserFilter;
import com.solesonic.repository.memory.ChatMemoryReflectionRepository;
import com.solesonic.repository.memory.MemoryAdminRepository;
import com.solesonic.repository.memory.MemoryEmbeddingRepository;
import com.solesonic.repository.memory.UserMemoryAdminRepository;
import com.solesonic.repository.memory.UserMemoryEvidenceRepository;
import com.solesonic.service.user.UserProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryAdminServiceTest {
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID OTHER_OWNER_ID = UUID.randomUUID();
    private static final PageRequest NEWEST_FIRST = PageRequest.of(0, 50,
            Sort.by(Sort.Direction.DESC, "updated").and(Sort.by("id")));

    @Mock
    private UserMemoryAdminRepository userMemoryAdminRepository;

    @Mock
    private UserMemoryEvidenceRepository userMemoryEvidenceRepository;

    @Mock
    private ChatMemoryReflectionRepository chatMemoryReflectionRepository;

    @Mock
    private MemoryAdminRepository memoryAdminRepository;

    @Mock
    private MemoryEmbeddingRepository memoryEmbeddingRepository;

    @Mock
    private UserProfileService userProfileService;

    private MemoryAdminService memoryAdminService;

    @BeforeEach
    void setUp() {
        memoryAdminService = new MemoryAdminService(userMemoryAdminRepository,
                userMemoryEvidenceRepository,
                chatMemoryReflectionRepository,
                memoryAdminRepository,
                memoryEmbeddingRepository,
                userProfileService,
                true,
                "qwen3.5-9b",
                "0 */5 * * * *",
                5,
                3,
                30,
                "qwen3-embedding",
                "0 * * * * *",
                50,
                true,
                MemoryRetrievalMode.RELEVANT);

        lenient().when(userProfileService.filter(any(), isNull()))
                .thenAnswer(invocation -> UserFilter.of(invocation.getArgument(0)));
    }

    /**
     * No filter means every status — superseded included — and every kind, newest change first.
     */
    @Test
    void listsEveryStatusAndKindNewestFirstWhenUnfiltered() {
        when(userMemoryAdminRepository.findByStatusInAndKindIn(EnumSet.allOf(MemoryStatus.class), EnumSet.allOf(MemoryKind.class), NEWEST_FIRST))
                .thenReturn(new PageImpl<>(List.of(), NEWEST_FIRST, 0));

        assertThat(memoryAdminService.listMemories(null, null, null, List.of(), PageRequest.of(0, 50))).isEmpty();
    }

    @Test
    void narrowsToOneUserWhenAsked() {
        when(userMemoryAdminRepository.findByUserIdAndStatusInAndKindIn(OWNER_ID, EnumSet.of(MemoryStatus.ACTIVE),
                EnumSet.allOf(MemoryKind.class), NEWEST_FIRST))
                .thenReturn(new PageImpl<>(List.of(), NEWEST_FIRST, 0));

        assertThat(memoryAdminService.listMemories(OWNER_ID, null, List.of(MemoryStatus.ACTIVE), null, PageRequest.of(0, 50))).isEmpty();
    }

    @Test
    void narrowsToTheUserAUsernameNames() {
        when(userProfileService.filter(null, "sam")).thenReturn(UserFilter.of(OWNER_ID));
        when(userMemoryAdminRepository.findByUserIdAndStatusInAndKindIn(OWNER_ID, EnumSet.allOf(MemoryStatus.class),
                EnumSet.allOf(MemoryKind.class), NEWEST_FIRST))
                .thenReturn(new PageImpl<>(List.of(), NEWEST_FIRST, 0));

        assertThat(memoryAdminService.listMemories(null, "sam", null, null, PageRequest.of(0, 50))).isEmpty();

        verify(userMemoryAdminRepository).findByUserIdAndStatusInAndKindIn(OWNER_ID, EnumSet.allOf(MemoryStatus.class),
                EnumSet.allOf(MemoryKind.class), NEWEST_FIRST);
    }

    /**
     * A username nobody holds is an empty page, never every user's memories.
     */
    @Test
    void listsNothingForAnUnknownUsername() {
        when(userProfileService.filter(null, "nobody")).thenReturn(UserFilter.NOBODY);
        when(userProfileService.filter(OWNER_ID, "nobody")).thenReturn(UserFilter.NOBODY);

        assertThat(memoryAdminService.listMemories(null, "nobody", null, null, PageRequest.of(0, 50))).isEmpty();
        assertThat(memoryAdminService.reflections(OWNER_ID, "nobody", null, PageRequest.of(0, 50))).isEmpty();

        verifyNoInteractions(userMemoryAdminRepository, chatMemoryReflectionRepository);
    }

    /**
     * The caller's sort is kept, with the id appended so ties never straddle a page boundary.
     */
    @Test
    void keepsTheCallersSortAndBreaksTiesById() {
        PageRequest requested = PageRequest.of(2, 10, Sort.by(Sort.Direction.ASC, "confidence"));
        PageRequest expected = PageRequest.of(2, 10, Sort.by(Sort.Direction.ASC, "confidence").and(Sort.by("id")));

        when(userMemoryAdminRepository.findByStatusInAndKindIn(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), expected, 0));

        memoryAdminService.listMemories(null, null, null, null, requested);

        verify(userMemoryAdminRepository).findByStatusInAndKindIn(EnumSet.allOf(MemoryStatus.class), EnumSet.allOf(MemoryKind.class), expected);
    }

    /**
     * A property outside the allow-list is the caller's mistake, not a query error — and content is
     * never sortable, so the order of rows cannot be used to probe what memories say.
     */
    @Test
    void refusesToSortByAnythingOutsideTheAllowList() {
        assertThatThrownBy(() -> memoryAdminService.listMemories(null, null, null, null, PageRequest.of(0, 50, Sort.by("content"))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verifyNoInteractions(userMemoryAdminRepository);
    }

    @Test
    void sortsReflectionsWithTheChatIdAsTieBreaker() {
        PageRequest requested = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "attempts"));
        PageRequest expected = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "attempts").and(Sort.by("chatId")));

        when(chatMemoryReflectionRepository.findByStatusIn(EnumSet.of(ReflectionStatus.FAILED), expected))
                .thenReturn(new PageImpl<>(List.of(), expected, 0));

        assertThat(memoryAdminService.reflections(null, null, List.of(ReflectionStatus.FAILED), requested)).isEmpty();
    }

    /**
     * Evidence is user-scoped, so each owner's memories have their chats read under that owner.
     */
    @Test
    void readsEachOwnersEvidenceUnderThatOwner() {
        UserMemory first = memory(OWNER_ID);
        UserMemory second = memory(OTHER_OWNER_ID);
        UUID firstChat = UUID.randomUUID();
        UUID secondChat = UUID.randomUUID();

        when(userMemoryAdminRepository.findByStatusInAndKindIn(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(first, second), NEWEST_FIRST, 2));
        when(userMemoryEvidenceRepository.findByUserIdAndMemoryIdIn(OWNER_ID, Set.of(first.getId())))
                .thenReturn(List.of(new UserMemoryEvidence(first.getId(), OWNER_ID, firstChat, UUID.randomUUID(), ZonedDateTime.now())));
        when(userMemoryEvidenceRepository.findByUserIdAndMemoryIdIn(OTHER_OWNER_ID, Set.of(second.getId())))
                .thenReturn(List.of(new UserMemoryEvidence(second.getId(), OTHER_OWNER_ID, secondChat, UUID.randomUUID(), ZonedDateTime.now())));
        when(userProfileService.usernames(List.of(OWNER_ID, OTHER_OWNER_ID))).thenReturn(Map.of(OWNER_ID, "sam"));

        List<MemorySummary> summaries = memoryAdminService.listMemories(null, null, null, null, PageRequest.of(0, 50)).getContent();

        assertThat(summaries).extracting(MemorySummary::chatIds)
                .containsExactly(List.of(firstChat), List.of(secondChat));
        assertThat(summaries).extracting(MemorySummary::userId).containsExactly(OWNER_ID, OTHER_OWNER_ID);
        assertThat(summaries).extracting(MemorySummary::username).containsExactly("sam", null);
    }

    @Test
    void namesEachReflectionsOwner() {
        ChatMemoryReflection first = reflection(OWNER_ID);
        ChatMemoryReflection second = reflection(OTHER_OWNER_ID);

        when(chatMemoryReflectionRepository.findByStatusIn(any(), any()))
                .thenReturn(new PageImpl<>(List.of(first, second), NEWEST_FIRST, 2));
        when(userProfileService.usernames(List.of(OWNER_ID, OTHER_OWNER_ID))).thenReturn(Map.of(OTHER_OWNER_ID, "alex"));

        assertThat(memoryAdminService.reflections(null, null, null, PageRequest.of(0, 50)).getContent())
                .extracting(ReflectionSummary::username)
                .containsExactly(null, "alex");
    }

    private static ChatMemoryReflection reflection(UUID ownerId) {
        ChatMemoryReflection reflection = new ChatMemoryReflection();
        reflection.setChatId(UUID.randomUUID());
        reflection.setUserId(ownerId);
        reflection.setStatus(ReflectionStatus.COMPLETED);
        reflection.setUpdated(ZonedDateTime.now());
        return reflection;
    }

    private static UserMemory memory(UUID ownerId) {
        ZonedDateTime now = ZonedDateTime.now();

        UserMemory memory = new UserMemory();
        memory.setId(UUID.randomUUID());
        memory.setUserId(ownerId);
        memory.setKind(MemoryKind.FACT);
        memory.setOrigin(MemoryOrigin.INFERRED);
        memory.setStatus(MemoryStatus.ACTIVE);
        memory.setContent("Lives in Austin");
        memory.setConfidence(0.8);
        memory.setCreated(now);
        memory.setUpdated(now);
        memory.setLastConfirmed(now);
        return memory;
    }
}
