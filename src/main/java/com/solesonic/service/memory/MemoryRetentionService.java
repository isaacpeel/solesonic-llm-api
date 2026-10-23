package com.solesonic.service.memory;

import com.solesonic.model.memory.MemoryOrigin;
import com.solesonic.model.memory.MemoryStatus;
import com.solesonic.model.memory.UserMemory;
import com.solesonic.repository.memory.UserMemoryAdminRepository;
import com.solesonic.repository.memory.UserMemoryRepository;
import com.solesonic.service.interest.InterestOfferWithdrawal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Removes inferred memories that never earned their place: a pending one nobody confirmed within
 * {@code pending-days}, and an active one whose decayed score has sunk below {@code min-score}.
 * What the user set themselves is never touched.
 * <p>
 * A retired memory goes with the superseded chain behind it. Those rows exist only so forgetting the
 * memory's conversation can restore what it replaced; once the memory itself has lapsed, nothing in
 * that chain is worth restoring.
 */
@Service
public class MemoryRetentionService {
    private static final Logger log = LoggerFactory.getLogger(MemoryRetentionService.class);

    private final UserMemoryRepository userMemoryRepository;
    private final UserMemoryAdminRepository userMemoryAdminRepository;
    private final InterestOfferWithdrawal interestOfferWithdrawal;
    private final Duration pendingRetention;
    private final double minScore;

    public MemoryRetentionService(UserMemoryRepository userMemoryRepository,
                                  UserMemoryAdminRepository userMemoryAdminRepository,
                                  InterestOfferWithdrawal interestOfferWithdrawal,
                                  @Value("${solesonic.llm.memory.retention.pending-days}") long pendingDays,
                                  @Value("${solesonic.llm.memory.retention.min-score}") double minScore) {
        this.userMemoryRepository = userMemoryRepository;
        this.userMemoryAdminRepository = userMemoryAdminRepository;
        this.interestOfferWithdrawal = interestOfferWithdrawal;
        this.pendingRetention = Duration.ofDays(pendingDays);
        this.minScore = minScore;
    }

    @Transactional(readOnly = true)
    public List<UUID> usersWithInferredMemories() {
        return userMemoryAdminRepository.findUserIdsByOriginAndStatusIn(MemoryOrigin.INFERRED, UserMemoryService.LIVE_STATUSES);
    }

    /**
     * @return how many live memories were retired
     */
    @Transactional
    public int retire(UUID userId) {
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime pendingCutoff = now.minus(pendingRetention);

        List<UserMemory> lapsed = userMemoryRepository.findByUserIdAndStatusInOrderByCreatedAsc(userId, UserMemoryService.LIVE_STATUSES)
                .stream()
                .filter(memory -> userId.equals(memory.getUserId()))
                .filter(memory -> memory.getOrigin() == MemoryOrigin.INFERRED)
                .filter(memory -> memory.getStatus() == MemoryStatus.PENDING
                        ? memory.getLastConfirmed().isBefore(pendingCutoff)
                        : MemoryRanking.score(memory, now) < minScore)
                .toList();

        if (lapsed.isEmpty()) {
            return 0;
        }

        Map<UUID, UserMemory> supersededById = userMemoryRepository
                .findByUserIdAndStatusInOrderByCreatedAsc(userId, Set.of(MemoryStatus.SUPERSEDED))
                .stream()
                .collect(Collectors.toMap(UserMemory::getId, Function.identity()));

        Set<UUID> retired = new HashSet<>();

        for (UserMemory memory : lapsed) {
            retired.add(memory.getId());

            UUID predecessorId = memory.getSupersedes();

            while (predecessorId != null && supersededById.containsKey(predecessorId) && retired.add(predecessorId)) {
                predecessorId = supersededById.get(predecessorId).getSupersedes();
            }
        }

        userMemoryRepository.deleteByUserIdAndIdIn(userId, retired);
        interestOfferWithdrawal.withdrawOrphaned(userId);

        log.info("Retired {} lapsed memory(ies) for user {} ({} row(s) with their superseded history)",
                lapsed.size(), userId, retired.size());

        return lapsed.size();
    }
}
