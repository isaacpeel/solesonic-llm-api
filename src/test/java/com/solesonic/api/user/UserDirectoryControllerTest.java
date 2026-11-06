package com.solesonic.api.user;

import com.solesonic.model.user.UserDirectoryEntry;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.user.UserDirectoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UserDirectoryControllerTest {
    private final UUID callerId = UUID.randomUUID();

    @Mock
    private UserDirectoryService userDirectoryService;

    @Mock
    private UserRequestContext userRequestContext;

    @InjectMocks
    private UserDirectoryController userDirectoryController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(userDirectoryController).build();
    }

    @Test
    void searchesAsTheCallerAndReturnsOnlyIdUsernameAndDisplayName() throws Exception {
        UUID daveId = UUID.randomUUID();
        when(userRequestContext.getUserId()).thenReturn(callerId);
        when(userDirectoryService.enabled()).thenReturn(true);
        when(userDirectoryService.search(callerId, "dav", 5))
                .thenReturn(List.of(new UserDirectoryEntry(daveId, "dave", "Dave Peel")));

        mockMvc.perform(get("/users/directory").param("q", "dav").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userId").value(daveId.toString()))
                .andExpect(jsonPath("$[0].username").value("dave"))
                .andExpect(jsonPath("$[0].displayName").value("Dave Peel"))
                .andExpect(jsonPath("$[0].email").doesNotExist());
    }

    @Test
    void queryAndLimitAreOptional() throws Exception {
        when(userRequestContext.getUserId()).thenReturn(callerId);
        when(userDirectoryService.enabled()).thenReturn(true);
        when(userDirectoryService.search(callerId, null, null)).thenReturn(List.of());

        mockMvc.perform(get("/users/directory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void answersNotFoundWhileDisabled() throws Exception {
        when(userDirectoryService.enabled()).thenReturn(false);

        mockMvc.perform(get("/users/directory").param("q", "a"))
                .andExpect(status().isNotFound());

        verify(userDirectoryService).enabled();
        verifyNoMoreInteractions(userDirectoryService);
    }
}
