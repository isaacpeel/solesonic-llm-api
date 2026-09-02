package com.solesonic.api.user;

import com.solesonic.model.user.UserPreferences;
import com.solesonic.service.security.ResourceOwnershipService;
import com.solesonic.service.user.UserPreferencesService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/users")
public class UserController {
    private final UserPreferencesService userPreferencesService;
    private final ResourceOwnershipService resourceOwnershipService;

    public UserController(UserPreferencesService userPreferencesService, ResourceOwnershipService resourceOwnershipService) {
        this.userPreferencesService = userPreferencesService;
        this.resourceOwnershipService = resourceOwnershipService;
    }

    @GetMapping("/{userId}/preferences")
    public ResponseEntity<UserPreferences> get(@PathVariable UUID userId, HttpServletRequest request) {
        if (!resourceOwnershipService.isOwner(userId, request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        UserPreferences userPreferences = userPreferencesService.get(userId);

        return ResponseEntity.ok(userPreferences);
    }

    @PostMapping("/{userId}/preferences")
    public ResponseEntity<UserPreferences> save(@PathVariable UUID userId, @RequestBody UserPreferences userPreferences, HttpServletRequest request) {
        if (!resourceOwnershipService.isOwner(userId, request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        UserPreferences saved = userPreferencesService.save(userId, userPreferences);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(saved.getUserId())
                .toUri();

        return ResponseEntity.created(location).body(userPreferences);
    }

    @PutMapping("/{userId}/preferences")
    public ResponseEntity<UserPreferences> update(@PathVariable UUID userId,
                                                  @RequestBody UserPreferences userPreferences,
                                                  HttpServletRequest request) {
        if (!resourceOwnershipService.isOwner(userId, request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        UserPreferences update = userPreferencesService.update(userId, userPreferences);
        return ResponseEntity.ok(update);
    }
}
