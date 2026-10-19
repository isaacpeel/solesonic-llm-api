package com.solesonic.api.memory;

import com.solesonic.model.memory.ReflectionStatus;
import com.solesonic.model.memory.ReflectionSummary;
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

/**
 * How far reflection has read into each conversation, across every user, for members of the
 * {@code izzy-bot-admin} Keycloak group.
 */
@RestController
@RequestMapping("/memories/reflections")
@PreAuthorize("hasAuthority('GROUP_izzy-bot-admin')")
public class MemoryReflectionController {
    private static final Logger log = LoggerFactory.getLogger(MemoryReflectionController.class);

    private static final int DEFAULT_PAGE_SIZE = 50;

    private final MemoryAdminService memoryAdminService;
    private final UserRequestContext userRequestContext;

    public MemoryReflectionController(MemoryAdminService memoryAdminService, UserRequestContext userRequestContext) {
        this.memoryAdminService = memoryAdminService;
        this.userRequestContext = userRequestContext;
    }

    /**
     * Newest change first unless {@code sort} says otherwise.
     */
    @GetMapping
    public ResponseEntity<PagedModel<ReflectionSummary>> list(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) List<ReflectionStatus> status,
            @PageableDefault(size = DEFAULT_PAGE_SIZE) Pageable pageable) {
        log.info("Admin {} listing reflections user {} status {} page {} size {} sort {}",
                userRequestContext.getUserId(), userId, status, pageable.getPageNumber(), pageable.getPageSize(), pageable.getSort());

        return ResponseEntity.ok(new PagedModel<>(memoryAdminService.reflections(userId, status, pageable)));
    }
}
