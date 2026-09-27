package com.solesonic.api.google;

import com.solesonic.model.google.broker.GoogleTokenExchange;
import com.solesonic.model.google.broker.GoogleTokenResponse;
import com.solesonic.service.google.GoogleTokenBrokerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/broker/google")
public class GoogleTokenBrokerController {

    private static final Logger log = LoggerFactory.getLogger(GoogleTokenBrokerController.class);

    private final GoogleTokenBrokerService googleTokenBrokerService;

    public GoogleTokenBrokerController(GoogleTokenBrokerService googleTokenBrokerService) {
        this.googleTokenBrokerService = googleTokenBrokerService;
    }

    @PostMapping("/token")
    @PreAuthorize("hasRole('token-mint-gmail')")
    public ResponseEntity<GoogleTokenResponse> token(@Validated @RequestBody GoogleTokenExchange request) {
        log.info("Google token mint request received");
        log.debug("Google token mint request received for userId: {}", request.subjectToken());

        GoogleTokenResponse response = googleTokenBrokerService.mintToken(request);

        return ResponseEntity.ok(response);
    }
}
