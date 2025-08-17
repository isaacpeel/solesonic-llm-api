package com.solesonic.api.atlassian;

import com.solesonic.model.atlassian.confluence.Space;
import com.solesonic.model.atlassian.confluence.SpacesResponse;
import com.solesonic.service.atlassian.ConfluenceSpaceService;
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
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
public class ConfluenceSpaceControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ConfluenceSpaceService confluenceSpaceService;

    @InjectMocks
    private ConfluenceSpaceController confluenceSpaceController;

    private Space space;
    private SpacesResponse spacesResponse;

    @BeforeEach
    void setUp() {
        // Set up Space
        space = new Space();
        space.setId("space-id-1");
        space.setName("Test Space");
        space.setKey("TEST");
        space.setCreatedAt(ZonedDateTime.parse("2025-04-25T03:18:09.386Z"));

        // Set up SpacesResponse
        spacesResponse = new SpacesResponse();
        spacesResponse.setResults(List.of(space));

        // Set up MockMvc
        mockMvc = MockMvcBuilders.standaloneSetup(confluenceSpaceController).build();
    }

    @Test
    void testGetSpaces() throws Exception {
        // Arrange
        when(confluenceSpaceService.getSpaces()).thenReturn(spacesResponse);

        // Act & Assert
        mockMvc.perform(get("/confluence/spaces"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.results[0].id").value("space-id-1"))
                .andExpect(jsonPath("$.results[0].name").value("Test Space"))
                .andExpect(jsonPath("$.results[0].key").value("TEST"));
    }

    @Test
    void testGetSpace() throws Exception {
        // Arrange
        when(confluenceSpaceService.getSpace("space-id-1")).thenReturn(space);

        // Act & Assert
        mockMvc.perform(get("/confluence/spaces/space-id-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value("space-id-1"))
                .andExpect(jsonPath("$.name").value("Test Space"))
                .andExpect(jsonPath("$.key").value("TEST"))
                .andExpect(jsonPath("$.createdAt").value("2025-04-25T03:18:09.386Z"));
    }

    @Test
    void testCreateSpace() throws Exception {
        when(confluenceSpaceService.createSpace(any(Space.class))).thenReturn(space);

        mockMvc.perform(post("/confluence/spaces")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Test Space\",\"key\":\"TEST\",\"description\":{\"plain\":{\"representation\":\"plain\",\"value\":\"This is a test space\"}}}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value("space-id-1"))
                .andExpect(jsonPath("$.name").value("Test Space"))
                .andExpect(jsonPath("$.key").value("TEST"));
    }
}
