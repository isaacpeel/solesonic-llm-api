package com.solesonic;

import com.solesonic.model.user.UserPreferences;
import com.solesonic.repository.UserPreferencesRepository;
import com.solesonic.scope.UserRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, SpringExtension.class})
public class IzzybotTest {

    @Mock
    protected UserRequestContext userRequestContext;

    @Mock
    protected UserPreferencesRepository userPreferencesRepository;

    protected UUID userId;
    protected UserPreferences userPreferences;

    @BeforeEach
    public void beforeEach() {
        // Set up test data
        userId = UUID.randomUUID();
        userPreferences = new UserPreferences();
        userPreferences.setUserId(userId);
        userPreferences.setModel("llama3");
        userPreferences.setSimilarityThreshold(0.7);
        userPreferences.setCreated(ZonedDateTime.now());
        userPreferences.setUpdated(ZonedDateTime.now());

        // Set up mocks
        when(userPreferencesRepository.findAll()).thenReturn(List.of(userPreferences));
        when(userPreferencesRepository.findByUserId(userId)).thenReturn(Optional.of(userPreferences));
        when(userRequestContext.getUserId()).thenReturn(userId);
    }
}
