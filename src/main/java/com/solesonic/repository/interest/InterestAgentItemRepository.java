package com.solesonic.repository.interest;

import com.solesonic.model.interest.InterestAgentItem;
import com.solesonic.model.interest.InterestAgentItemId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface InterestAgentItemRepository extends JpaRepository<InterestAgentItem, InterestAgentItemId> {

    @Query("""
            select item.urlHash
              from InterestAgentItem item
             where item.agentId = :agentId
               and item.userId = :userId
               and item.urlHash in :urlHashes
            """)
    List<String> findSeen(UUID agentId, UUID userId, Collection<String> urlHashes);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from InterestAgentItem item where item.firstSeen < :firstSeenBefore")
    int deleteByFirstSeenBefore(ZonedDateTime firstSeenBefore);
}
