package com.solesonic.api.push;

import com.solesonic.exception.push.PushException;
import com.solesonic.model.push.PushPreferenceRequest;
import com.solesonic.model.push.PushPreferenceSummary;
import com.solesonic.service.push.PushPreferenceService;
import com.solesonic.service.security.ResourceOwnershipService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Which events a user wants pushed. Deliberately not part of {@code UserPreferences}: that endpoint
 * is a full replace, and a client that does not send these fields would switch notifications off.
 */
@RestController
@RequestMapping("/users/{userId}/push-preferences")
public class PushPreferenceController {

    private final PushPreferenceService pushPreferenceService;
    private final ResourceOwnershipService resourceOwnershipService;

    public PushPreferenceController(PushPreferenceService pushPreferenceService,
                                    ResourceOwnershipService resourceOwnershipService) {
        this.pushPreferenceService = pushPreferenceService;
        this.resourceOwnershipService = resourceOwnershipService;
    }

    @GetMapping
    public PushPreferenceSummary get(@PathVariable UUID userId, HttpServletRequest request) {
        requireCaller(userId, request);

        return pushPreferenceService.get(userId);
    }

    @PutMapping
    public PushPreferenceSummary update(@PathVariable UUID userId,
                                        @RequestBody PushPreferenceRequest pushPreferenceRequest,
                                        HttpServletRequest request) {
        requireCaller(userId, request);

        return pushPreferenceService.update(userId, pushPreferenceRequest);
    }

    private void requireCaller(UUID userId, HttpServletRequest request) {
        if (!resourceOwnershipService.isOwner(userId, request)) {
            throw PushException.notFound();
        }
    }
}
