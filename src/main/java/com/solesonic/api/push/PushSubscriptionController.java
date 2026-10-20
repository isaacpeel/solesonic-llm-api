package com.solesonic.api.push;

import com.solesonic.exception.push.PushException;
import com.solesonic.model.push.PushSubscriptionRequest;
import com.solesonic.model.push.PushSubscriptionSummary;
import com.solesonic.service.push.PushSubscriptionService;
import com.solesonic.service.security.ResourceOwnershipService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * A user's browser push subscriptions. A {@code {userId}} that is not the caller is a {@code 404} on
 * every method, never a {@code 403}: whether another user has subscriptions is not the caller's to
 * learn.
 */
@RestController
@RequestMapping("/users/{userId}/push-subscriptions")
public class PushSubscriptionController {

    private final PushSubscriptionService pushSubscriptionService;
    private final ResourceOwnershipService resourceOwnershipService;

    public PushSubscriptionController(PushSubscriptionService pushSubscriptionService,
                                      ResourceOwnershipService resourceOwnershipService) {
        this.pushSubscriptionService = pushSubscriptionService;
        this.resourceOwnershipService = resourceOwnershipService;
    }

    @GetMapping
    public List<PushSubscriptionSummary> list(@PathVariable UUID userId, HttpServletRequest request) {
        requireCaller(userId, request);

        return pushSubscriptionService.list(userId);
    }

    @PutMapping
    public ResponseEntity<PushSubscriptionSummary> upsert(@PathVariable UUID userId,
                                                          @RequestBody PushSubscriptionRequest pushSubscriptionRequest,
                                                          @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
                                                          HttpServletRequest request) {
        requireCaller(userId, request);

        PushSubscriptionService.UpsertResult upsertResult = pushSubscriptionService.upsert(userId, pushSubscriptionRequest, userAgent);

        return ResponseEntity.status(upsertResult.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(upsertResult.summary());
    }

    @DeleteMapping("/{subscriptionId}")
    public ResponseEntity<Void> delete(@PathVariable UUID userId, @PathVariable UUID subscriptionId, HttpServletRequest request) {
        requireCaller(userId, request);

        pushSubscriptionService.delete(userId, subscriptionId);

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{subscriptionId}/test")
    public ResponseEntity<Void> test(@PathVariable UUID userId, @PathVariable UUID subscriptionId, HttpServletRequest request) {
        requireCaller(userId, request);

        pushSubscriptionService.test(userId, subscriptionId);

        return ResponseEntity.noContent().build();
    }

    private void requireCaller(UUID userId, HttpServletRequest request) {
        if (!resourceOwnershipService.isOwner(userId, request)) {
            throw PushException.notFound();
        }
    }
}
