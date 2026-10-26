package com.solesonic.api.llm;

import com.solesonic.model.llm.UserLlmKeyRequest;
import com.solesonic.model.llm.UserLlmKeySummary;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.llm.UserLlmKeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Each user's model-server key, for holders of the {@code llm-key-admin} role only. A key is created
 * in the model server's own admin UI and registered here; it is write-only, and no response ever
 * carries it.
 */
@RestController
@RequestMapping("/llm-keys")
@PreAuthorize("hasRole('llm-key-admin')")
public class UserLlmKeyController {
    private static final Logger log = LoggerFactory.getLogger(UserLlmKeyController.class);

    private static final int DEFAULT_PAGE_SIZE = 50;

    private final UserLlmKeyService userLlmKeyService;
    private final UserRequestContext userRequestContext;

    public UserLlmKeyController(UserLlmKeyService userLlmKeyService, UserRequestContext userRequestContext) {
        this.userLlmKeyService = userLlmKeyService;
        this.userRequestContext = userRequestContext;
    }

    /**
     * Most recently changed first.
     */
    @GetMapping
    public ResponseEntity<PagedModel<UserLlmKeySummary>> list(@PageableDefault(size = DEFAULT_PAGE_SIZE) Pageable pageable) {
        Pageable keyPage = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());

        log.info("Admin {} listing model access keys page {} size {}",
                userRequestContext.getUserId(), keyPage.getPageNumber(), keyPage.getPageSize());

        return ResponseEntity.ok(new PagedModel<>(userLlmKeyService.list(keyPage)));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<UserLlmKeySummary> get(@PathVariable UUID userId) {
        return ResponseEntity.of(userLlmKeyService.summary(userId));
    }

    /**
     * Registers or replaces the user's key, once the model server has accepted it.
     */
    @PutMapping("/{userId}")
    public ResponseEntity<UserLlmKeySummary> register(@PathVariable UUID userId,
                                                      @RequestBody UserLlmKeyRequest userLlmKeyRequest) {
        log.info("Admin {} registering a model access key for user {}", userRequestContext.getUserId(), userId);

        return ResponseEntity.ok(userLlmKeyService.register(userId, userLlmKeyRequest.apiKey(), userRequestContext.getUserId()));
    }

    /**
     * The user loses model access on their next call.
     */
    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> delete(@PathVariable UUID userId) {
        log.info("Admin {} removing the model access key of user {}", userRequestContext.getUserId(), userId);

        if (!userLlmKeyService.delete(userId, userRequestContext.getUserId())) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.noContent().build();
    }
}
