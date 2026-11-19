package com.solesonic.repository.agentupdate;

import com.solesonic.model.agentupdate.AgentUpdate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

public interface AgentUpdateRepository extends JpaRepository<AgentUpdate, UUID> {

    Optional<AgentUpdate> findByAgentAndIdempotencyKey(String agent, String idempotencyKey);

    long countByUserIdAndCreatedAfter(UUID userId, ZonedDateTime since);
}
