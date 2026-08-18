package com.solesonic.api.google;

import com.solesonic.model.google.auth.GoogleAuthLinkResponse;
import com.solesonic.service.google.GoogleAuthService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/google")
public class GoogleAuthController {
    private final GoogleAuthService googleAuthService;

    public GoogleAuthController(GoogleAuthService googleAuthService) {
        this.googleAuthService = googleAuthService;
    }

    @GetMapping("/auth/uri")
    public ResponseEntity<GoogleAuthLinkResponse> getGoogleAuthUri() {
        String authUri = googleAuthService.authUri();
        GoogleAuthLinkResponse googleAuthLinkResponse = new GoogleAuthLinkResponse(authUri);

        return ResponseEntity.ok(googleAuthLinkResponse);
    }

    @GetMapping("/auth/callback")
    public ResponseEntity<Void> googleCallback(@RequestParam String code) {
        googleAuthService.callback(code);

        return ResponseEntity.noContent().build();
    }

    /**
     * {@code produces} is load-bearing: the body is a JSON string, and without it Spring's
     * {@code StringHttpMessageConverter} serves it as {@code text/plain}. A client that decides
     * whether to parse by inspecting the content type then silently receives nothing.
     */
    @GetMapping(value = "/auth/profile", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> profile() {
        String profile = googleAuthService.profile();

        return ResponseEntity.ok(profile);
    }

    @PostMapping("/auth/revoke")
    public ResponseEntity<Void> revoke() {
        googleAuthService.revoke();

        return ResponseEntity.noContent().build();
    }
}
