package com.solesonic.izzybot.api.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.solesonic.izzybot.model.user.UserPreferences;
import com.solesonic.izzybot.service.user.UserPreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.ZonedDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
public class UserControllerTest {

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Mock
    private UserPreferencesService userPreferencesService;

    @InjectMocks
    private UserController userController;

    private UUID userId;
    private UserPreferences userPreferences;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();

        // Set up UserPreferences
        userPreferences = new UserPreferences();
        userPreferences.setUserId(userId);
        userPreferences.setModel("llama3");
        userPreferences.setSimilarityThreshold(0.7);
        userPreferences.setCreated(ZonedDateTime.now());
        userPreferences.setUpdated(ZonedDateTime.now());

        // Set up MockMvc
        mockMvc = MockMvcBuilders.standaloneSetup(userController).build();
    }

    @Test
    void testGetUserPreferences() throws Exception {
        // Arrange
        when(userPreferencesService.get(userId)).thenReturn(userPreferences);

        // Act & Assert
        mockMvc.perform(get("/users/{userId}/preferences", userId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.model").value("llama3"))
                .andExpect(jsonPath("$.similarityThreshold").value(0.7));
    }

    @Test
    void testSaveUserPreferences() throws Exception {
        // Arrange
        when(userPreferencesService.save(eq(userId), any(UserPreferences.class))).thenReturn(userPreferences);

        // Act & Assert
        mockMvc.perform(post("/users/{userId}/preferences", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(userPreferences)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.model").value("llama3"))
                .andExpect(jsonPath("$.similarityThreshold").value(0.7));
    }

    @Test
    void testUpdateUserPreferences() throws Exception {
        // Arrange
        when(userPreferencesService.update(eq(userId), any(UserPreferences.class))).thenReturn(userPreferences);

        // Act & Assert
        mockMvc.perform(put("/users/{userId}/preferences", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(userPreferences)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.model").value("llama3"))
                .andExpect(jsonPath("$.similarityThreshold").value(0.7));
    }
}
