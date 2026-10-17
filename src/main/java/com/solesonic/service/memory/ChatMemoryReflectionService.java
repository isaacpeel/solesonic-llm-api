package com.solesonic.service.memory;

import com.solesonic.model.memory.ChatMemoryReflection;
import com.solesonic.model.memory.ReflectionStatus;
import com.solesonic.repository.memory.ChatMemoryReflectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The reflection cursor's bookkeeping, each step its own short transaction so that no lock is held
 * across a model call.
 */
@Service
public class ChatMemoryReflectionService {
    private final ChatMemoryReflectionRepository chatMemoryReflectionRepository;

    public ChatMemoryReflectionService(ChatMemoryReflectionRepository chatMemoryReflectionRepository) {
        this.chatMemoryReflectionRepository = chatMemoryReflectionRepository;
    }

    /**
     * Claims up to {@code batchSize} quiet conversations, oldest unreflected message first. The rows
     * are locked with {@code skip locked} for as long as it takes to mark them {@code IN_PROGRESS},
     * which is what keeps two instances from reflecting the same chat.
     */
    @Transactional
    public List<ChatMemoryReflection> claim(ZonedDateTime idleBefore, int batchSize) {
        chatMemoryReflectionRepository.seedMissing();

        List<ChatMemoryReflection> claimed = chatMemoryReflectionRepository.findClaimable(idleBefore, batchSize);
        ZonedDateTime now = ZonedDateTime.now();

        for (ChatMemoryReflection reflection : claimed) {
            reflection.setStatus(ReflectionStatus.IN_PROGRESS);
            reflection.setUpdated(now);
        }

        return chatMemoryReflectionRepository.saveAll(claimed);
    }

    /**
     * Hands back a claim that found nothing to read, so it is not left {@code IN_PROGRESS}.
     */
    @Transactional
    public void release(UUID chatId, UUID userId) {
        chatMemoryReflectionRepository.findByChatIdAndUserId(chatId, userId)
                .filter(reflection -> reflection.getStatus() == ReflectionStatus.IN_PROGRESS)
                .ifPresent(reflection -> {
                    reflection.setStatus(ReflectionStatus.COMPLETED);
                    reflection.setUpdated(ZonedDateTime.now());
                    chatMemoryReflectionRepository.save(reflection);
                });
    }

    /**
     * Drops the row of a chat that is gone or whose owner no longer matches: nothing may be learned
     * from it.
     */
    @Transactional
    public void remove(UUID chatId, UUID userId) {
        chatMemoryReflectionRepository.findByChatIdAndUserId(chatId, userId)
                .ifPresent(chatMemoryReflectionRepository::delete);
    }

    /**
     * A failure never changes existing memories; it only counts against the chat, which stops being
     * retried once it has failed {@code maxAttempts} times.
     */
    @Transactional
    public void recordFailure(UUID chatId, UUID userId, int maxAttempts) {
        chatMemoryReflectionRepository.findByChatIdAndUserId(chatId, userId)
                .ifPresent(reflection -> {
                    int attempts = reflection.getAttempts() + 1;

                    reflection.setAttempts(attempts);
                    reflection.setStatus(attempts >= maxAttempts ? ReflectionStatus.FAILED : ReflectionStatus.PENDING);
                    reflection.setUpdated(ZonedDateTime.now());

                    chatMemoryReflectionRepository.save(reflection);
                });
    }
}
