package com.solesonic.api.atlassian;

import com.solesonic.model.atlassian.broker.TokenExchange;
import com.solesonic.model.atlassian.broker.TokenResponse;
import com.solesonic.service.atlassian.AtlassianTokenBrokerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/broker/atlassian")
public class AtlassianTokenBrokerController {

    private static final Logger log = LoggerFactory.getLogger(AtlassianTokenBrokerController.class);

    private final AtlassianTokenBrokerService tokenBrokerService;

    public AtlassianTokenBrokerController(AtlassianTokenBrokerService tokenBrokerService) {
        this.tokenBrokerService = tokenBrokerService;
    }

    @PostMapping("/token")
    public ResponseEntity<TokenResponse> token(@Validated @RequestBody TokenExchange request) {
        log.debug("Token mint request received for userId: {}, siteId: {}", request.subjectToken(), request.audience());

        TokenResponse response = tokenBrokerService.mintToken(request);

        return ResponseEntity.ok(response);
    }
}