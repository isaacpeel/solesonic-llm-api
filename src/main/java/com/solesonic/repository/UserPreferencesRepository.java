package com.solesonic.repository;

import com.solesonic.model.user.UserPreferences;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface UserPreferencesRepository extends JpaRepository<UserPreferences, UUID> {
    @Query("""
            from UserPreferences userPreferences where userPreferences.userId = :userId
            """)
    Optional<UserPreferences> findByUserId(UUID userId);

    @Query("""
            select userPreferences from UserPreferences userPreferences
            join Chat chat on chat.userId = userPreferences.userId
            where chat.id = :chatId
    """)
    Optional<UserPreferences> findByChatId(UUID chatId);
}
