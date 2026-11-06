package com.solesonic.api.push;

import com.solesonic.exception.handler.PushExceptionHandler;
import com.solesonic.model.push.PushPreference;
import com.solesonic.repository.push.PushPreferenceRepository;
import com.solesonic.service.push.PushPreferenceService;
import com.solesonic.service.security.ResourceOwnershipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs the real {@link PushPreferenceService} so the {@code 400} for a missing field is the whole
 * path, not a mocked exception.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PushPreferenceControllerTest {
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();

    @Mock
    private PushPreferenceRepository pushPreferenceRepository;

    @Mock
    private ResourceOwnershipService resourceOwnershipService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PushPreferenceService pushPreferenceService = new PushPreferenceService(pushPreferenceRepository,
                Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC));

        mockMvc = MockMvcBuilders
                .standaloneSetup(new PushPreferenceController(pushPreferenceService, resourceOwnershipService))
                .setControllerAdvice(new PushExceptionHandler())
                .build();

        when(resourceOwnershipService.isOwner(eq(USER_ID), any())).thenReturn(true);
        when(resourceOwnershipService.isOwner(eq(OTHER_USER_ID), any())).thenReturn(false);
        when(pushPreferenceRepository.save(any(PushPreference.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void readsBothOnWhenNothingIsStored() throws Exception {
        when(pushPreferenceRepository.findById(USER_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get("/users/{userId}/push-preferences", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turnComplete").value(true))
                .andExpect(jsonPath("$.elicitation").value(true))
                .andExpect(jsonPath("$.interestOffers").value(true))
                .andExpect(jsonPath("$.interestUpdates").value(true));
    }

    @Test
    void storesBothPreferences() throws Exception {
        mockMvc.perform(put("/users/{userId}/push-preferences", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"turnComplete\":false,\"elicitation\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turnComplete").value(false))
                .andExpect(jsonPath("$.elicitation").value(true));
    }

    @Test
    void refusesAMissingField() throws Exception {
        mockMvc.perform(put("/users/{userId}/push-preferences", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"turnComplete\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PREFERENCES"));

        verify(pushPreferenceRepository, never()).save(any());
    }

    @Test
    void anotherUsersPreferencesAreNotFound() throws Exception {
        mockMvc.perform(get("/users/{userId}/push-preferences", OTHER_USER_ID))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/users/{userId}/push-preferences", OTHER_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"turnComplete\":false,\"elicitation\":false}"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(pushPreferenceRepository);
    }
}
