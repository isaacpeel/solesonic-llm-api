package com.solesonic.repository.push;

import com.solesonic.model.push.PushPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Keyed by the owner's id, so a lookup by id is already scoped to that user.
 */
public interface PushPreferenceRepository extends JpaRepository<PushPreference, UUID> {
}
