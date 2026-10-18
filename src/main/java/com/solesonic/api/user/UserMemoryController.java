package com.solesonic.api.user;

import com.solesonic.model.memory.UserMemoryRequest;
import com.solesonic.model.memory.UserMemorySummary;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.memory.UserMemoryService;
import com.solesonic.service.security.ResourceOwnershipService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static com.solesonic.security.SecurityConfig.IZZY_BOT_ADMIN;

/**
 * A user's memories: what has been learned about them, and the means to correct it.
 * <p>
 * The {@code {userId}} path segment must be the caller, the same as every other
 * {@code /users/{userId}} endpoint — or the caller must be in the {@code izzy-bot-admin} group, which
 * manages any user's memories through these same endpoints and under the same rules. Every service
 * query is scoped to the path's user, so another user's memory id on this path is a {@code 404}
 * whoever asks. An administrator acting on someone else's memories is logged as such.
 */
@RestController
@RequestMapping("/users/{userId}/memories")
public class UserMemoryController {
    private static final Logger log = LoggerFactory.getLogger(UserMemoryController.class);

    private final UserMemoryService userMemoryService;
    private final ResourceOwnershipService resourceOwnershipService;
    private final UserRequestContext userRequestContext;

    public UserMemoryController(UserMemoryService userMemoryService,
                                ResourceOwnershipService resourceOwnershipService,
                                UserRequestContext userRequestContext) {
        this.userMemoryService = userMemoryService;
        this.resourceOwnershipService = resourceOwnershipService;
        this.userRequestContext = userRequestContext;
    }

    @GetMapping
    public ResponseEntity<List<UserMemorySummary>> list(@PathVariable UUID userId, HttpServletRequest request) {
        if (!mayManage(userId, request, "listing memories")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        return ResponseEntity.ok(userMemoryService.list(userId));
    }

    @GetMapping("/{memoryId}")
    public ResponseEntity<UserMemorySummary> get(@PathVariable UUID userId, @PathVariable UUID memoryId, HttpServletRequest request) {
        if (!mayManage(userId, request, "reading memory " + memoryId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        return ResponseEntity.ok(userMemoryService.get(userId, memoryId));
    }

    @PostMapping
    public ResponseEntity<UserMemorySummary> create(@PathVariable UUID userId,
                                                    @RequestBody UserMemoryRequest userMemoryRequest,
                                                    HttpServletRequest request) {
        if (!mayManage(userId, request, "creating a memory")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        UserMemorySummary created = userMemoryService.create(userId, userMemoryRequest);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{memoryId}")
                .buildAndExpand(created.id())
                .toUri();

        return ResponseEntity.created(location).body(created);
    }

    @PatchMapping("/{memoryId}")
    public ResponseEntity<UserMemorySummary> update(@PathVariable UUID userId,
                                                    @PathVariable UUID memoryId,
                                                    @RequestBody UserMemoryRequest userMemoryRequest,
                                                    HttpServletRequest request) {
        if (!mayManage(userId, request, "editing memory " + memoryId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        return ResponseEntity.ok(userMemoryService.update(userId, memoryId, userMemoryRequest));
    }

    @DeleteMapping("/{memoryId}")
    public ResponseEntity<Void> delete(@PathVariable UUID userId, @PathVariable UUID memoryId, HttpServletRequest request) {
        if (!mayManage(userId, request, "deleting memory " + memoryId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        userMemoryService.delete(userId, memoryId);

        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> deleteAll(@PathVariable UUID userId, HttpServletRequest request) {
        if (!mayManage(userId, request, "deleting every memory")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        userMemoryService.deleteAll(userId);

        return ResponseEntity.noContent().build();
    }

    private boolean mayManage(UUID userId, HttpServletRequest request, String action) {
        if (!resourceOwnershipService.isOwnerOrHasAuthority(userId, IZZY_BOT_ADMIN, request)) {
            return false;
        }

        UUID callerId = userRequestContext.getUserId();

        if (!userId.equals(callerId)) {
            log.info("Admin {} {} for user {}", callerId, action, userId);
        }

        return true;
    }
}
