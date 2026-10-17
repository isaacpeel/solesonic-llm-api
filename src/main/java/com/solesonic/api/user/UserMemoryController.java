package com.solesonic.api.user;

import com.solesonic.model.memory.UserMemoryRequest;
import com.solesonic.model.memory.UserMemorySummary;
import com.solesonic.service.memory.UserMemoryService;
import com.solesonic.service.security.ResourceOwnershipService;
import jakarta.servlet.http.HttpServletRequest;
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

/**
 * The memory panel: what has been learned about the caller, and the means to correct it.
 * <p>
 * The {@code {userId}} path segment is checked against the caller first, the same as every other
 * {@code /users/{userId}} endpoint, and every service query is scoped to it as well, so another
 * user's memory id on the caller's own path is a {@code 404}.
 */
@RestController
@RequestMapping("/users/{userId}/memories")
public class UserMemoryController {
    private final UserMemoryService userMemoryService;
    private final ResourceOwnershipService resourceOwnershipService;

    public UserMemoryController(UserMemoryService userMemoryService, ResourceOwnershipService resourceOwnershipService) {
        this.userMemoryService = userMemoryService;
        this.resourceOwnershipService = resourceOwnershipService;
    }

    @GetMapping
    public ResponseEntity<List<UserMemorySummary>> list(@PathVariable UUID userId, HttpServletRequest request) {
        if (!resourceOwnershipService.isOwner(userId, request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        return ResponseEntity.ok(userMemoryService.list(userId));
    }

    @PostMapping
    public ResponseEntity<UserMemorySummary> create(@PathVariable UUID userId,
                                                    @RequestBody UserMemoryRequest userMemoryRequest,
                                                    HttpServletRequest request) {
        if (!resourceOwnershipService.isOwner(userId, request)) {
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
        if (!resourceOwnershipService.isOwner(userId, request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        return ResponseEntity.ok(userMemoryService.update(userId, memoryId, userMemoryRequest));
    }

    @DeleteMapping("/{memoryId}")
    public ResponseEntity<Void> delete(@PathVariable UUID userId, @PathVariable UUID memoryId, HttpServletRequest request) {
        if (!resourceOwnershipService.isOwner(userId, request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        userMemoryService.delete(userId, memoryId);

        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> deleteAll(@PathVariable UUID userId, HttpServletRequest request) {
        if (!resourceOwnershipService.isOwner(userId, request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        userMemoryService.deleteAll(userId);

        return ResponseEntity.noContent().build();
    }
}
