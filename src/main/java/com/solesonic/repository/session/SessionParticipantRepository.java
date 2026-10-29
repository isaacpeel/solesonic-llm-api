package com.solesonic.repository.session;

import com.solesonic.model.session.SessionParticipant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SessionParticipantRepository extends JpaRepository<SessionParticipant, UUID> {

    List<SessionParticipant> findBySessionIdOrderBySeatAsc(UUID sessionId);

    List<SessionParticipant> findBySessionIdInOrderBySeatAsc(Collection<UUID> sessionIds);
}
