package com.solesonic.api.push;

import com.solesonic.config.push.PushSettings;
import com.solesonic.config.push.VapidKeys;
import com.solesonic.exception.handler.PushExceptionHandler;
import com.solesonic.service.push.webpush.P256Keys;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VapidKeyControllerTest {
    private final KeyPair keyPair = P256Keys.generate();
    private final VapidKeys vapidKeys = new VapidKeys((ECPublicKey) keyPair.getPublic(), (ECPrivateKey) keyPair.getPrivate(),
            "mailto:operator@example.com");

    private MockMvc mockMvc(boolean enabled) {
        PushSettings pushSettings = new PushSettings(enabled, Set.of("fcm.googleapis.com"), 3, 5, Duration.ofSeconds(120),
                Duration.ofSeconds(600), Duration.ofSeconds(45), Duration.ofSeconds(15));

        return MockMvcBuilders.standaloneSetup(new VapidKeyController(vapidKeys, pushSettings))
                .setControllerAdvice(new PushExceptionHandler())
                .build();
    }

    @Test
    void servesThePublicKey() throws Exception {
        mockMvc(true).perform(get("/push/vapid-public-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicKey").value(vapidKeys.publicKeyBase64Url()));
    }

    @Test
    void answersServiceUnavailableWhileDisabled() throws Exception {
        mockMvc(false).perform(get("/push/vapid-public-key"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PUSH_DISABLED"));
    }
}
