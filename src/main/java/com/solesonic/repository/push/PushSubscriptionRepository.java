package com.solesonic.repository.push;

import com.solesonic.model.push.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every method takes the owner except {@link #findByEndpoint}, which the upsert needs precisely
 * because an endpoint may currently belong to someone else.
 */
public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, UUID> {

    Optional<PushSubscription> findByEndpoint(String endpoint);

    List<PushSubscription> findByUserIdOrderByCreatedDesc(UUID userId);

    Optional<PushSubscription> findByIdAndUserId(UUID id, UUID userId);

    @Transactional
    @Modifying
    @Query("delete from PushSubscription subscription where subscription.id = :id and subscription.userId = :userId")
    int deleteByIdAndUserId(UUID id, UUID userId);

    @Transactional
    @Modifying
    @Query("""
            update PushSubscription subscription
               set subscription.lastDelivered = :delivered, subscription.failureCount = 0
             where subscription.id = :id and subscription.userId = :userId
            """)
    void markDelivered(UUID id, UUID userId, ZonedDateTime delivered);

    @Transactional
    @Modifying
    @Query("""
            update PushSubscription subscription
               set subscription.failureCount = subscription.failureCount + 1
             where subscription.id = :id and subscription.userId = :userId
            """)
    void incrementFailureCount(UUID id, UUID userId);
}
