package com.solesonic.api.memory;

import com.solesonic.model.memory.MemoryKind;
import com.solesonic.model.memory.MemoryProcessingStatus;
import com.solesonic.model.memory.MemoryStatus;
import com.solesonic.model.memory.MemorySummary;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.memory.MemoryAdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static com.solesonic.security.SecurityConfig.IZZY_BOT_ADMIN;

/**
 * Memories across every user, for members of the {@code izzy-bot-admin} Keycloak group. Read-only:
 * a memory belongs to a user, so it is changed through {@code /users/{userId}/memories/{memoryId}},
 * which admits the same group.
 * <p>
 * The gate is on the class, not each method: there is nothing here an ordinary user may call.
 */
@RestController
@RequestMapping("/memories")
@PreAuthorize("hasAuthority('" + IZZY_BOT_ADMIN + "')")
public class MemoryController {
    private static final Logger log = LoggerFactory.getLogger(MemoryController.class);

    private static final int DEFAULT_PAGE_SIZE = 50;

    private final MemoryAdminService memoryAdminService;
    private final UserRequestContext userRequestContext;

    public MemoryController(MemoryAdminService memoryAdminService, UserRequestContext userRequestContext) {
        this.memoryAdminService = memoryAdminService;
        this.userRequestContext = userRequestContext;
    }

    /**
     * Every user's memories, newest change first unless {@code sort} says otherwise, superseded ones
     * included unless {@code status} filters them out.
     */
    @GetMapping
    public ResponseEntity<PagedModel<MemorySummary>> list(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) List<MemoryStatus> status,
            @RequestParam(required = false) List<MemoryKind> kind,
            @PageableDefault(size = DEFAULT_PAGE_SIZE) Pageable pageable) {
        log.info("Admin {} listing memories user {} status {} kind {} page {} size {} sort {}",
                userRequestContext.getUserId(), userId, status, kind, pageable.getPageNumber(), pageable.getPageSize(), pageable.getSort());

        return ResponseEntity.ok(new PagedModel<>(memoryAdminService.listMemories(userId, status, kind, pageable)));
    }

    /**
     * Reflection, embedding and injection across all users, with the settings each runs under, and
     * per user.
     */
    @GetMapping("/status")
    public ResponseEntity<MemoryProcessingStatus> status() {
        log.info("Admin {} reading memory processing status", userRequestContext.getUserId());

        return ResponseEntity.ok(memoryAdminService.status());
    }
}
