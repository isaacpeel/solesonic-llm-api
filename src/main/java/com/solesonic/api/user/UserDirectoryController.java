package com.solesonic.api.user;

import com.solesonic.model.user.UserDirectoryEntry;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.user.UserDirectoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Who a user can share a chat with. Any signed-in user may search; the caller is never in the result,
 * and neither is anyone's email. Answers {@code 404} while the directory is switched off, so a
 * disabled deployment looks like one without the endpoint.
 */
@RestController
@RequestMapping("/users/directory")
public class UserDirectoryController {
    private final UserDirectoryService userDirectoryService;
    private final UserRequestContext userRequestContext;

    public UserDirectoryController(UserDirectoryService userDirectoryService, UserRequestContext userRequestContext) {
        this.userDirectoryService = userDirectoryService;
        this.userRequestContext = userRequestContext;
    }

    @GetMapping
    public ResponseEntity<List<UserDirectoryEntry>> search(@RequestParam(required = false) String q,
                                                           @RequestParam(required = false) Integer limit) {
        if (!userDirectoryService.enabled()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(userDirectoryService.search(userRequestContext.getUserId(), q, limit));
    }
}
