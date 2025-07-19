package com.solesonic.izzybot.repository;

import com.solesonic.izzybot.model.user.UserPreferences;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface UserPreferencesRepository extends JpaRepository<UserPreferences, UUID> {
    @Query("""
            from UserPreferences userPreferences where userPreferences.userId = :userId
            """)
    Optional<UserPreferences> findByUserId(UUID userId);
}
