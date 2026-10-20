package com.solesonic.service.push.webpush;

import com.solesonic.exception.push.WebPushException;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;

/**
 * RFC 8291 message encryption: one RFC 8188 {@code aes128gcm} record, keyed by ECDH between a fresh
 * application-server key and the browser's {@code p256dh}, mixed with its {@code auth} secret.
 */
public final class WebPushEncryption {
    public static final int RECORD_SIZE = 4096;
    public static final int SALT_LENGTH = 16;
    public static final int AUTH_SECRET_LENGTH = 16;
    public static final int HEADER_LENGTH = SALT_LENGTH + Integer.BYTES + 1 + P256Keys.PUBLIC_KEY_LENGTH;

    private static final int TAG_LENGTH = 16;
    private static final int TAG_BITS = TAG_LENGTH * Byte.SIZE;
    private static final int INPUT_KEYING_MATERIAL_LENGTH = 32;
    private static final int CONTENT_ENCRYPTION_KEY_LENGTH = 16;
    private static final int NONCE_LENGTH = 12;
    private static final byte LAST_RECORD_DELIMITER = 0x02;

    /**
     * Push services accept at most 4096 bytes of body, and the body carries the header, the
     * delimiter and the tag as well as the plaintext.
     */
    public static final int MAX_PLAINTEXT_LENGTH = RECORD_SIZE - HEADER_LENGTH - 1 - TAG_LENGTH;

    private static final String HMAC = "HmacSHA256";
    private static final byte[] KEY_INFO_PREFIX = "WebPush: info\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CONTENT_ENCRYPTION_KEY_INFO = "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NONCE_INFO = "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private WebPushEncryption() {
    }

    public static byte[] encrypt(byte[] plaintext, byte[] userAgentPublicKey, byte[] authSecret) {
        byte[] salt = new byte[SALT_LENGTH];
        SECURE_RANDOM.nextBytes(salt);

        return encrypt(plaintext, userAgentPublicKey, authSecret, P256Keys.generate(), salt);
    }

    static byte[] encrypt(byte[] plaintext,
                          byte[] userAgentPublicKey,
                          byte[] authSecret,
                          KeyPair applicationServerKeyPair,
                          byte[] salt) {

        if (plaintext.length > MAX_PLAINTEXT_LENGTH) {
            throw new IllegalArgumentException("A push payload is at most " + MAX_PLAINTEXT_LENGTH + " bytes");
        }

        if (authSecret == null || authSecret.length != AUTH_SECRET_LENGTH) {
            throw new IllegalArgumentException("A push auth secret is 16 bytes");
        }

        ECPublicKey userAgentKey = P256Keys.publicKey(userAgentPublicKey);
        byte[] applicationServerPublicKey = P256Keys.uncompressed((ECPublicKey) applicationServerKeyPair.getPublic());

        try {
            KeyAgreement keyAgreement = KeyAgreement.getInstance("ECDH");
            keyAgreement.init(applicationServerKeyPair.getPrivate());
            keyAgreement.doPhase(userAgentKey, true);
            byte[] ecdhSecret = keyAgreement.generateSecret();

            byte[] keyPseudoRandomKey = hmac(authSecret, ecdhSecret);
            byte[] keyInfo = concat(KEY_INFO_PREFIX, userAgentPublicKey, applicationServerPublicKey);
            byte[] inputKeyingMaterial = expand(keyPseudoRandomKey, keyInfo, INPUT_KEYING_MATERIAL_LENGTH);

            byte[] pseudoRandomKey = hmac(salt, inputKeyingMaterial);
            byte[] contentEncryptionKey = expand(pseudoRandomKey, CONTENT_ENCRYPTION_KEY_INFO, CONTENT_ENCRYPTION_KEY_LENGTH);
            byte[] nonce = expand(pseudoRandomKey, NONCE_INFO, NONCE_LENGTH);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(contentEncryptionKey, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(concat(plaintext, new byte[]{LAST_RECORD_DELIMITER}));

            return ByteBuffer.allocate(HEADER_LENGTH + ciphertext.length)
                    .put(salt)
                    .putInt(RECORD_SIZE)
                    .put((byte) applicationServerPublicKey.length)
                    .put(applicationServerPublicKey)
                    .put(ciphertext)
                    .array();
        } catch (GeneralSecurityException generalSecurityException) {
            throw new WebPushException("Could not encrypt the push message", generalSecurityException);
        }
    }

    /**
     * HKDF-Expand for a single output block, which every length used here fits in.
     */
    private static byte[] expand(byte[] pseudoRandomKey, byte[] info, int length) throws GeneralSecurityException {
        return Arrays.copyOf(hmac(pseudoRandomKey, concat(info, new byte[]{0x01})), length);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(HMAC);
        mac.init(new SecretKeySpec(key, HMAC));

        return mac.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;

        for (byte[] part : parts) {
            length += part.length;
        }

        ByteBuffer buffer = ByteBuffer.allocate(length);

        for (byte[] part : parts) {
            buffer.put(part);
        }

        return buffer.array();
    }
}
