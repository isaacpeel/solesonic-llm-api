package com.solesonic.service.memory;

import com.solesonic.repository.chat.ChatMessageRepository;
import com.solesonic.repository.chat.ChatRepository;
import com.solesonic.repository.memory.ChatMemoryReflectionRepository;
import com.solesonic.repository.memory.UserMemoryEvidenceRepository;
import com.solesonic.repository.memory.UserMemoryRepository;

import static org.mockito.Mockito.mock;

final class UserMemoryServiceFixture {
    static final int MAX_MEMORY_CHARACTERS = 500;
    static final int MAX_ACTIVE_MEMORIES = 3;
    static final int PROMPT_MAX_CHARACTERS = 400;

    private UserMemoryServiceFixture() {
    }

    static UserMemoryService service(UserMemoryRepository userMemoryRepository,
                                     UserMemoryEvidenceRepository userMemoryEvidenceRepository,
                                     ChatMemoryReflectionRepository chatMemoryReflectionRepository) {
        return service(userMemoryRepository, userMemoryEvidenceRepository, chatMemoryReflectionRepository, true);
    }

    static UserMemoryService service(UserMemoryRepository userMemoryRepository,
                                     UserMemoryEvidenceRepository userMemoryEvidenceRepository,
                                     ChatMemoryReflectionRepository chatMemoryReflectionRepository,
                                     boolean injectionEnabled) {
        return service(userMemoryRepository,
                userMemoryEvidenceRepository,
                chatMemoryReflectionRepository,
                mock(ChatRepository.class),
                mock(ChatMessageRepository.class),
                injectionEnabled);
    }

    static UserMemoryService service(UserMemoryRepository userMemoryRepository,
                                     UserMemoryEvidenceRepository userMemoryEvidenceRepository,
                                     ChatMemoryReflectionRepository chatMemoryReflectionRepository,
                                     ChatRepository chatRepository,
                                     ChatMessageRepository chatMessageRepository,
                                     boolean injectionEnabled) {
        return new UserMemoryService(userMemoryRepository,
                userMemoryEvidenceRepository,
                chatMemoryReflectionRepository,
                chatRepository,
                chatMessageRepository,
                MAX_MEMORY_CHARACTERS,
                MAX_ACTIVE_MEMORIES,
                injectionEnabled,
                PROMPT_MAX_CHARACTERS);
    }
}
