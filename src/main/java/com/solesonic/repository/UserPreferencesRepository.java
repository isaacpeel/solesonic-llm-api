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
            from UserPreferences userPreferences,
                 Chat chat
            where chat.id = :chatId
            and chat.userId = userPreferences.userId
    """)
    Optional<UserPreferences> findByChatId(UUID chatId);
}
