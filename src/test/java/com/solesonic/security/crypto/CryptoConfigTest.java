package com.solesonic.security.crypto;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.encrypt.BytesEncryptor;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class CryptoConfigTest {
    private static final String PASSWORD = "test-password";
    private static final String SALT = "5c0744940b5c369b";

    /**
     * Produced by the removed {@code Encryptors.stronger(PASSWORD, SALT)}. Refresh tokens already at
     * rest were written this way, so the replacement must still read them.
     */
    private static final String LEGACY_CIPHERTEXT =
            "9db73c3d1ac14415785c052a49983865e2c60fbd739a235ea196c7cf765f1386fc67ea2e58b00b10c623f7b3a2f170e84884f3";

    private final BytesEncryptor bytesEncryptor = new CryptoConfig().bytesEncryptor(PASSWORD, SALT);

    @Test
    void decryptsWhatTheDeprecatedEncryptorWrote() {
        byte[] decrypted = bytesEncryptor.decrypt(HexFormat.of().parseHex(LEGACY_CIPHERTEXT));

        assertThat(new String(decrypted, StandardCharsets.UTF_8)).isEqualTo("refresh-token-value");
    }

    @Test
    void roundTrips() {
        byte[] encrypted = bytesEncryptor.encrypt("access-token".getBytes(StandardCharsets.UTF_8));

        assertThat(new String(bytesEncryptor.decrypt(encrypted), StandardCharsets.UTF_8)).isEqualTo("access-token");
    }
}
