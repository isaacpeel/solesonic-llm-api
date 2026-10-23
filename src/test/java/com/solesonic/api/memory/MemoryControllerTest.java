package com.solesonic.api.memory;

import com.solesonic.model.memory.MemoryKind;
import com.solesonic.model.memory.MemoryOrigin;
import com.solesonic.model.memory.MemoryStatus;
import com.solesonic.model.memory.MemorySummary;
import com.solesonic.model.memory.ReflectionStatus;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.memory.MemoryAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Routing, paging and response shapes of {@code /memories} and {@code /memories/reflections}. A
 * standalone {@code MockMvc} applies no method security; the gate is
 * {@link MemoryMethodSecurityTest}'s.
 */
@ExtendWith(MockitoExtension.class)
class MemoryControllerTest {
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID MEMORY_ID = UUID.randomUUID();
    private static final String USERNAME = "sam";

    @Mock
    private MemoryAdminService memoryAdminService;

    @Mock
    private UserRequestContext userRequestContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new MemoryController(memoryAdminService, userRequestContext),
                        new MemoryReflectionController(memoryAdminService, userRequestContext))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
    }

    static MemorySummary summary() {
        ZonedDateTime now = ZonedDateTime.now();

        return new MemorySummary(MEMORY_ID, USER_ID, USERNAME, MemoryKind.FACT, MemoryOrigin.INFERRED, MemoryStatus.ACTIVE,
                "home-city", "Lives in Austin", 0.8, now, now, now, null, List.of());
    }

    @Test
    void listsEveryUsersMemoriesWithTheirOwner() throws Exception {
        when(memoryAdminService.listMemories(eq(null), eq(List.of(MemoryStatus.SUPERSEDED)), eq(null), any()))
                .thenReturn(new PageImpl<>(List.of(summary()), PageRequest.of(0, 50), 1));

        mockMvc.perform(get("/memories").param("status", "SUPERSEDED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(MEMORY_ID.toString()))
                .andExpect(jsonPath("$.content[0].userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.content[0].username").value(USERNAME))
                .andExpect(jsonPath("$.content[0].memoryKey").value("home-city"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    /**
     * {@code page}, {@code size} and repeated {@code sort} reach the service as one {@code Pageable}.
     */
    @Test
    void passesPagingAndSortThrough() throws Exception {
        PageRequest expected = PageRequest.of(2, 10, Sort.by(Sort.Order.asc("userId"), Sort.Order.desc("confidence")));

        when(memoryAdminService.listMemories(USER_ID, List.of(MemoryStatus.ACTIVE, MemoryStatus.PENDING), List.of(MemoryKind.FACT), expected))
                .thenReturn(new PageImpl<>(List.of(), expected, 0));

        mockMvc.perform(get("/memories")
                        .param("userId", USER_ID.toString())
                        .param("status", "ACTIVE", "PENDING")
                        .param("kind", "FACT")
                        .param("page", "2")
                        .param("size", "10")
                        .param("sort", "userId,asc")
                        .param("sort", "confidence,desc"))
                .andExpect(status().isOk());

        verify(memoryAdminService).listMemories(USER_ID, List.of(MemoryStatus.ACTIVE, MemoryStatus.PENDING), List.of(MemoryKind.FACT), expected);
    }

    @Test
    void defaultsToFiftyAPage() throws Exception {
        when(memoryAdminService.listMemories(null, null, null, PageRequest.of(0, 50)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        mockMvc.perform(get("/memories")).andExpect(status().isOk());

        verify(memoryAdminService).listMemories(null, null, null, PageRequest.of(0, 50));
    }

    @Test
    void readsTheProcessingStatus() throws Exception {
        mockMvc.perform(get("/memories/status")).andExpect(status().isOk());

        verify(memoryAdminService).status();
    }

    @Test
    void listsReflectionsFilteredAndPaged() throws Exception {
        PageRequest expected = PageRequest.of(1, 20, Sort.by(Sort.Order.desc("attempts")));

        when(memoryAdminService.reflections(USER_ID, List.of(ReflectionStatus.FAILED), expected))
                .thenReturn(new PageImpl<>(List.of(), expected, 0));

        mockMvc.perform(get("/memories/reflections")
                        .param("userId", USER_ID.toString())
                        .param("status", "FAILED")
                        .param("page", "1")
                        .param("size", "20")
                        .param("sort", "attempts,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.number").value(1));

        verify(memoryAdminService).reflections(USER_ID, List.of(ReflectionStatus.FAILED), expected);
    }
}
