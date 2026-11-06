package com.solesonic.api.push;

import com.solesonic.config.push.PushSettings;
import com.solesonic.config.push.VapidKeys;
import com.solesonic.exception.push.PushException;
import com.solesonic.model.push.VapidPublicKey;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The deployment's VAPID public key, which a browser needs to subscribe. A {@code 503} tells the UI
 * to hide the feature.
 */
@RestController
@RequestMapping("/push")
public class VapidKeyController {

    private final VapidKeys vapidKeys;
    private final PushSettings pushSettings;

    public VapidKeyController(VapidKeys vapidKeys, PushSettings pushSettings) {
        this.vapidKeys = vapidKeys;
        this.pushSettings = pushSettings;
    }

    @GetMapping("/vapid-public-key")
    public VapidPublicKey vapidPublicKey() {
        if (!pushSettings.enabled()) {
            throw PushException.disabled();
        }

        return new VapidPublicKey(vapidKeys.publicKeyBase64Url());
    }
}
