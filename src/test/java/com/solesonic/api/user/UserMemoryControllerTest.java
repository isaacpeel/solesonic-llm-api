package com.solesonic.api.user;

import com.solesonic.model.memory.MemoryKind;
import com.solesonic.model.memory.MemoryOrigin;
import com.solesonic.model.memory.MemoryStatus;
import com.solesonic.model.memory.UserMemoryRequest;
import com.solesonic.model.memory.UserMemorySummary;
import com.solesonic.service.memory.UserMemoryService;
import com.solesonic.service.security.ResourceOwnershipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UserMemoryControllerTest {
    private final UUID userId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();
    private final UUID memoryId = UUID.randomUUID();

    @Mock
    private UserMemoryService userMemoryService;

    @Mock
    private ResourceOwnershipService resourceOwnershipService;

    @InjectMocks
    private UserMemoryController userMemoryController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        lenient().when(resourceOwnershipService.isOwner(eq(userId), any())).thenReturn(true);
        lenient().when(resourceOwnershipService.isOwner(eq(otherUserId), any())).thenReturn(false);

        mockMvc = MockMvcBuilders.standaloneSetup(userMemoryController).build();
    }

    @Test
    void listsTheCallersMemories() throws Exception {
        when(userMemoryService.list(userId)).thenReturn(List.of(summary()));

        mockMvc.perform(get("/users/{userId}/memories", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(memoryId.toString()))
                .andExpect(jsonPath("$[0].content").value("Prefers metric units"))
                .andExpect(jsonPath("$[0].userId").doesNotExist())
                .andExpect(jsonPath("$[0].memoryKey").doesNotExist());
    }

    @Test
    void createsAnAuthoredMemory() throws Exception {
        when(userMemoryService.create(eq(userId), any(UserMemoryRequest.class))).thenReturn(summary());

        mockMvc.perform(post("/users/{userId}/memories", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'kind':'PREFERENCE','content':'Prefers metric units'}")))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").value(memoryId.toString()));

        verify(userMemoryService).create(userId, new UserMemoryRequest(MemoryKind.PREFERENCE, "Prefers metric units"));
    }

    @Test
    void editsAMemory() throws Exception {
        when(userMemoryService.update(eq(userId), eq(memoryId), any(UserMemoryRequest.class))).thenReturn(summary());

        mockMvc.perform(patch("/users/{userId}/memories/{memoryId}", userId, memoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'content':'Prefers metric units'}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.origin").value("AUTHORED"));
    }

    @Test
    void deletesOneMemory() throws Exception {
        mockMvc.perform(delete("/users/{userId}/memories/{memoryId}", userId, memoryId))
                .andExpect(status().isNoContent());

        verify(userMemoryService).delete(userId, memoryId);
    }

    @Test
    void deletesAllMemories() throws Exception {
        mockMvc.perform(delete("/users/{userId}/memories", userId))
                .andExpect(status().isNoContent());

        verify(userMemoryService).deleteAll(userId);
    }

    /**
     * Another user's path is refused before the service is reached, on every method.
     */
    @Test
    void refusesEveryMethodOnAnotherUsersPath() throws Exception {
        mockMvc.perform(get("/users/{userId}/memories", otherUserId)).andExpect(status().isForbidden());
        mockMvc.perform(post("/users/{userId}/memories", otherUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'kind':'FACT','content':'x'}")))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/users/{userId}/memories/{memoryId}", otherUserId, memoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'content':'x'}")))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/users/{userId}/memories/{memoryId}", otherUserId, memoryId)).andExpect(status().isForbidden());
        mockMvc.perform(delete("/users/{userId}/memories", otherUserId)).andExpect(status().isForbidden());

        verifyNoInteractions(userMemoryService);
    }

    /**
     * On the caller's own path, another user's memory id is simply not found: the service's queries
     * are scoped to the caller, so it cannot be told apart from one that does not exist.
     */
    @Test
    void anotherUsersMemoryIdOnTheCallersOwnPathIsNotFound() throws Exception {
        when(userMemoryService.update(eq(userId), eq(memoryId), any(UserMemoryRequest.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

        mockMvc.perform(patch("/users/{userId}/memories/{memoryId}", userId, memoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'content':'x'}")))
                .andExpect(status().isNotFound());

        verify(userMemoryService, never()).update(eq(otherUserId), any(), any());
    }

    private static String json(String singleQuoted) {
        return singleQuoted.replace('\'', '"');
    }

    private UserMemorySummary summary() {
        ZonedDateTime now = ZonedDateTime.now();

        return new UserMemorySummary(memoryId, MemoryKind.PREFERENCE, MemoryOrigin.AUTHORED, MemoryStatus.ACTIVE,
                "Prefers metric units", 1.0, now, now, now, List.of());
    }
}
