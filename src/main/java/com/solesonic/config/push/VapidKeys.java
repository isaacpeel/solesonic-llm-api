package com.solesonic.config.push;

import com.solesonic.service.push.webpush.P256Keys;
import org.jspecify.annotations.NonNull;

import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;

/**
 * The deployment's VAPID identity. Rotating it invalidates every browser subscription.
 */
public record VapidKeys(ECPublicKey publicKey, ECPrivateKey privateKey, String subject) {

    public String publicKeyBase64Url() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(P256Keys.uncompressed(publicKey));
    }

    @Override
    public @NonNull String toString() {
        return "VapidKeys[subject=" + subject + "]";
    }
}
