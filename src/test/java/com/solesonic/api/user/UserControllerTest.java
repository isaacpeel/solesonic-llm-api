package com.solesonic.api.user;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.solesonic.model.atlassian.auth.AtlassianAccessToken;
import com.solesonic.model.google.auth.GoogleAccessToken;
import com.solesonic.model.user.UserPreferences;
import com.solesonic.service.user.UserPreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.ZonedDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
public class UserControllerTest {

    private MockMvc mockMvc;

    private final JsonMapper jsonMapper = JsonMapper.builder()
            .changeDefaultPropertyInclusion(incl ->
                    incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

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
        userPreferences.setChatSimilarityThreshold(0.5);
        userPreferences.setUserSimilarityThreshold(0.7);
        userPreferences.setGlobalSimilarityThreshold(0.7);
        userPreferences.setCreated(ZonedDateTime.now());
        userPreferences.setUpdated(ZonedDateTime.now());

        // Set up MockMvc
        mockMvc = MockMvcBuilders.standaloneSetup(userController).build();
    }

    @Test
    void testGetUserPreferences() throws Exception {
        
        when(userPreferencesService.get(userId)).thenReturn(userPreferences);

         
        mockMvc.perform(get("/users/{userId}/preferences", userId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.model").value("llama3"))
                .andExpect(jsonPath("$.chatSimilarityThreshold").value(0.5))
                .andExpect(jsonPath("$.userSimilarityThreshold").value(0.7))
                .andExpect(jsonPath("$.globalSimilarityThreshold").value(0.7));
    }

    @Test
    void testSaveUserPreferences() throws Exception {
        
        when(userPreferencesService.save(eq(userId), any(UserPreferences.class))).thenReturn(userPreferences);

         
        mockMvc.perform(post("/users/{userId}/preferences", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(userPreferences)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.model").value("llama3"))
                .andExpect(jsonPath("$.chatSimilarityThreshold").value(0.5))
                .andExpect(jsonPath("$.userSimilarityThreshold").value(0.7))
                .andExpect(jsonPath("$.globalSimilarityThreshold").value(0.7));
    }

    @Test
    void testUpdateUserPreferences() throws Exception {
        
        when(userPreferencesService.update(eq(userId), any(UserPreferences.class))).thenReturn(userPreferences);

         
        mockMvc.perform(put("/users/{userId}/preferences", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(userPreferences)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.model").value("llama3"))
                .andExpect(jsonPath("$.chatSimilarityThreshold").value(0.5))
                .andExpect(jsonPath("$.userSimilarityThreshold").value(0.7))
                .andExpect(jsonPath("$.globalSimilarityThreshold").value(0.7));
    }

    /**
     * The response body must never carry either integration's tokens. This endpoint serializes the
     * JPA entity directly, so a field added without {@code @JsonIgnore} silently publishes an
     * access <em>and refresh</em> token to the browser. The connection state the client actually
     * needs travels as a boolean instead.
     */
    @Test
    void neverSerializesStoredTokens() throws Exception {
        userPreferences.setAtlassianAccessToken(AtlassianAccessToken.builder()
                .accessToken("atlassian-access-token")
                .refreshToken("atlassian-refresh-token")
                .build());

        userPreferences.setGoogleAccessToken(GoogleAccessToken.builder()
                .accessToken("google-access-token")
                .refreshToken("google-refresh-token")
                .build());

        userPreferences.setAtlassianAuthentication(true);
        userPreferences.setGoogleAuthentication(true);

        when(userPreferencesService.get(userId)).thenReturn(userPreferences);

        String responseBody = mockMvc.perform(get("/users/{userId}/preferences", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.atlassianAccessToken").doesNotExist())
                .andExpect(jsonPath("$.googleAccessToken").doesNotExist())
                .andExpect(jsonPath("$.atlassianAuthentication").value(true))
                .andExpect(jsonPath("$.googleAuthentication").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(responseBody)
                .doesNotContain("atlassian-access-token")
                .doesNotContain("atlassian-refresh-token")
                .doesNotContain("google-access-token")
                .doesNotContain("google-refresh-token");
    }
}
