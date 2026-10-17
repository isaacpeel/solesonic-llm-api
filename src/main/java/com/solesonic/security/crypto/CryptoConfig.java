package com.solesonic.security.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.encrypt.AesGcmBytesEncryptor;
import org.springframework.security.crypto.encrypt.BytesEncryptor;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

@Configuration
public class CryptoConfig {

    @Bean
    public BytesEncryptor bytesEncryptor(@Value("${encryption.password}") String password,
                                         @Value("${encryption.salt}") String salt) {
        return AesGcmBytesEncryptor.withSecretKey(storedTokenKey(password, salt)).build();
    }

    /**
     * Derived exactly as the deprecated {@code Encryptors.stronger} derived it — PBKDF2 with
     * HMAC-SHA1, 1024 iterations, a 256-bit key — because the tokens already at rest were encrypted
     * under it. {@code AesGcmBytesEncryptor.withPassword} derives a different key and would leave
     * every stored token unreadable.
     */
    private static SecretKey storedTokenKey(String password, String salt) {
        PBEKeySpec keySpec = new PBEKeySpec(password.toCharArray(), HexFormat.of().parseHex(salt), 1024, 256);

        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(keySpec);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not derive the token encryption key", exception);
        } finally {
            keySpec.clearPassword();
        }
    }
}
