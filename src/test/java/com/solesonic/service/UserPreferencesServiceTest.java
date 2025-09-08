package com.solesonic.service;

import com.solesonic.model.user.UserPreferences;
import com.solesonic.repository.UserPreferencesRepository;
import com.solesonic.service.atlassian.AtlassianTokenStore;
import com.solesonic.service.user.UserPreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class UserPreferencesServiceTest {

    @Mock
    private UserPreferencesRepository userPreferencesRepository;

    @Mock
    private AtlassianTokenStore atlassianTokenStore;

    @InjectMocks
    private UserPreferencesService userPreferencesService;

    private UUID userId;
    private UserPreferences userPreferences;
    private final String defaultChatModel = "llama3";
    private final Double defaultSimilarityThreshold = 0.7;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();

        // Set up UserPreferences
        userPreferences = new UserPreferences();
        userPreferences.setUserId(userId);
        userPreferences.setModel(defaultChatModel);
        userPreferences.setSimilarityThreshold(defaultSimilarityThreshold);
        userPreferences.setCreated(ZonedDateTime.now());
        userPreferences.setUpdated(ZonedDateTime.now());

        // Set default values using ReflectionTestUtils
        ReflectionTestUtils.setField(userPreferencesService, "chatModel", defaultChatModel);
        ReflectionTestUtils.setField(userPreferencesService, "similarityThreshold", defaultSimilarityThreshold);
    }

    @Test
    void testGetExistingUserPreferences() {
        // Arrange
        when(userPreferencesRepository.findByUserId(userId)).thenReturn(Optional.of(userPreferences));
        when(atlassianTokenStore.exists(userId)).thenReturn(true);

        // Act
        UserPreferences result = userPreferencesService.get(userId);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(userId);
        assertThat(result.getModel()).isEqualTo(defaultChatModel);
        assertThat(result.getSimilarityThreshold()).isEqualTo(defaultSimilarityThreshold);
        assertThat(result.isAtlassianAuthentication()).isTrue();
        verify(userPreferencesRepository).findByUserId(userId);
        verify(atlassianTokenStore).exists(userId);
    }

    @Test
    void testGetNonExistingUserPreferences() {
        // Arrange
        when(userPreferencesRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(userPreferencesRepository.saveAndFlush(any(UserPreferences.class))).thenReturn(userPreferences);
        when(atlassianTokenStore.exists(userId)).thenReturn(false);

        // Act
        UserPreferences result = userPreferencesService.get(userId);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(userId);
        assertThat(result.getModel()).isEqualTo(defaultChatModel);
        assertThat(result.getSimilarityThreshold()).isEqualTo(defaultSimilarityThreshold);
        assertThat(result.isAtlassianAuthentication()).isFalse();
        verify(userPreferencesRepository).findByUserId(userId);
        verify(userPreferencesRepository).saveAndFlush(any(UserPreferences.class));
        verify(atlassianTokenStore).exists(userId);
    }

    @Test
    void testFindAll() {
        // Arrange
        UserPreferences anotherUserPreferences = new UserPreferences();
        anotherUserPreferences.setUserId(UUID.randomUUID());
        List<UserPreferences> allPreferences = Arrays.asList(userPreferences, anotherUserPreferences);
        
        when(userPreferencesRepository.findAll()).thenReturn(allPreferences);

        // Act
        List<UserPreferences> result = userPreferencesService.findAll();

        // Assert
        assertThat(result).isNotNull();
        assertThat(result).hasSize(2);
        assertThat(result).contains(userPreferences, anotherUserPreferences);
        verify(userPreferencesRepository).findAll();
    }

    @Test
    void testSave() {
        // Arrange
        when(userPreferencesRepository.saveAndFlush(any(UserPreferences.class))).thenReturn(userPreferences);

        // Act
        UserPreferences result = userPreferencesService.save(userId, userPreferences);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(userId);
        verify(userPreferencesRepository).saveAndFlush(any(UserPreferences.class));
    }

    @Test
    void testUpdate() {
        // Arrange
        when(userPreferencesRepository.save(any(UserPreferences.class))).thenReturn(userPreferences);

        // Act
        UserPreferences result = userPreferencesService.update(userId, userPreferences);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(userId);
        verify(userPreferencesRepository).save(any(UserPreferences.class));
    }
}