package com.solesonic.service;

import com.solesonic.model.atlassian.auth.AtlassianAccessToken;
import com.solesonic.model.user.UserPreferences;
import com.solesonic.repository.UserPreferencesRepository;
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

        userPreferences.setAtlassianAccessToken(new AtlassianAccessToken(
                UUID.randomUUID(),
                "accessToken",
                "refreshToken",
                "tokenType",
                "scope",
                1,
                false,
                null,
                null,
                null,
                "error"
        ));

        // Set default values using ReflectionTestUtils
        ReflectionTestUtils.setField(userPreferencesService, "chatModel", defaultChatModel);
        ReflectionTestUtils.setField(userPreferencesService, "similarityThreshold", defaultSimilarityThreshold);
    }

    @Test
    void testGetExistingUserPreferences() {
        when(userPreferencesRepository.findByUserId(userId)).thenReturn(Optional.of(userPreferences));
        UserPreferences result = userPreferencesService.get(userId);

        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(userId);
        assertThat(result.getModel()).isEqualTo(defaultChatModel);
        assertThat(result.getSimilarityThreshold()).isEqualTo(defaultSimilarityThreshold);
        assertThat(result.isAtlassianAuthentication()).isTrue();
        verify(userPreferencesRepository).findByUserId(userId);
    }

    @Test
    void testGetNonExistingUserPreferences() {
        
        when(userPreferencesRepository.findByUserId(userId)).thenReturn(Optional.empty());

        userPreferences.setAtlassianAccessToken(null);
        when(userPreferencesRepository.saveAndFlush(any(UserPreferences.class))).thenReturn(userPreferences);

        UserPreferences result = userPreferencesService.get(userId);

        
        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(userId);
        assertThat(result.getModel()).isEqualTo(defaultChatModel);
        assertThat(result.getSimilarityThreshold()).isEqualTo(defaultSimilarityThreshold);
        assertThat(result.isAtlassianAuthentication()).isFalse();
        verify(userPreferencesRepository).findByUserId(userId);
        verify(userPreferencesRepository).saveAndFlush(any(UserPreferences.class));
    }

    @Test
    void testFindAll() {
        
        UserPreferences anotherUserPreferences = new UserPreferences();
        anotherUserPreferences.setUserId(UUID.randomUUID());
        List<UserPreferences> allPreferences = Arrays.asList(userPreferences, anotherUserPreferences);
        
        when(userPreferencesRepository.findAll()).thenReturn(allPreferences);

        
        List<UserPreferences> result = userPreferencesService.findAll();

        
        assertThat(result).isNotNull();
        assertThat(result).hasSize(2);
        assertThat(result).contains(userPreferences, anotherUserPreferences);
        verify(userPreferencesRepository).findAll();
    }

    @Test
    void testSave() {
        
        when(userPreferencesRepository.saveAndFlush(any(UserPreferences.class))).thenReturn(userPreferences);

        
        UserPreferences result = userPreferencesService.save(userId, userPreferences);

        
        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(userId);
        verify(userPreferencesRepository).saveAndFlush(any(UserPreferences.class));
    }

    @Test
    void testUpdate() {
        
        when(userPreferencesRepository.save(any(UserPreferences.class))).thenReturn(userPreferences);

        
        UserPreferences result = userPreferencesService.update(userId, userPreferences);

        
        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(userId);
        verify(userPreferencesRepository).save(any(UserPreferences.class));
    }
}