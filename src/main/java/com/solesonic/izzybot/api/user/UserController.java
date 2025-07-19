package com.solesonic.izzybot.api.user;

import com.solesonic.izzybot.model.user.UserPreferences;
import com.solesonic.izzybot.service.user.UserPreferencesService;
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
@RequestMapping("/izzybot/users")
public class UserController {
    private final UserPreferencesService userPreferencesService;

    public UserController(UserPreferencesService userPreferencesService) {
        this.userPreferencesService = userPreferencesService;
    }

    @GetMapping("/{userId}/preferences")
    public ResponseEntity<UserPreferences> get(@PathVariable UUID userId) {
        UserPreferences userPreferences = userPreferencesService.get(userId);

        return ResponseEntity.ok(userPreferences);
    }

    @PostMapping("/{userId}/preferences")
    public ResponseEntity<UserPreferences> save(@PathVariable UUID userId, @RequestBody UserPreferences userPreferences) {
        UserPreferences saved = userPreferencesService.save(userId, userPreferences);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(saved.getUserId())
                .toUri();

        return ResponseEntity.created(location).body(userPreferences);
    }

    @PutMapping("/{userId}/preferences")
    public ResponseEntity<UserPreferences> update(@PathVariable UUID userId,
                                                  @RequestBody UserPreferences userPreferences) {
        UserPreferences update = userPreferencesService.update(userId, userPreferences);
        return ResponseEntity.ok(update);
    }
}
