package com.solesonic.service.interest;

import com.solesonic.repository.interest.InterestOfferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Takes back a pending offer once none of the memories it came from is still live. Kept apart from
 * {@link InterestOfferService} and built on repositories alone because {@code UserMemoryService}
 * calls it, and anything that reached the chat services from here would close a bean cycle.
 */
@Component
public class InterestOfferWithdrawal {
    private static final Logger log = LoggerFactory.getLogger(InterestOfferWithdrawal.class);

    private final InterestOfferRepository interestOfferRepository;
    private final Clock clock;

    public InterestOfferWithdrawal(InterestOfferRepository interestOfferRepository, Clock clock) {
        this.interestOfferRepository = interestOfferRepository;
        this.clock = clock;
    }

    /**
     * Joins the caller's transaction, so a forget and the withdrawal it causes commit together.
     */
    @Transactional
    public void withdrawOrphaned(UUID userId) {
        int withdrawn = interestOfferRepository.withdrawOrphaned(userId, ZonedDateTime.now(clock));

        if (withdrawn > 0) {
            log.info("Withdrew {} offer(s) for user {}: their memories are gone", withdrawn, userId);
        }
    }

    @Transactional
    public int withdrawAllOrphaned() {
        return interestOfferRepository.withdrawAllOrphaned(ZonedDateTime.now(clock));
    }
}
