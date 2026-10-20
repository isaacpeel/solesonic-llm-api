package com.solesonic.service.push.webpush;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the encryption to RFC 8291 Appendix A. A single wrong byte anywhere in the key schedule changes
 * the whole message, so matching it is the proof that a browser can decrypt what we send.
 */
class WebPushEncryptionTest {
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();
    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private static final String PLAINTEXT = "When I grow up, I want to be a watermelon";
    private static final String APPLICATION_SERVER_PRIVATE_KEY = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    private static final String APPLICATION_SERVER_PUBLIC_KEY =
            "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    private static final String USER_AGENT_PUBLIC_KEY =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
    private static final String AUTH_SECRET = "BTBZMqHH6r4Tts7J_aSIgg";
    private static final String MESSAGE = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIg"
            + "Dll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQex"
            + "SgSxsj_Qulcy4a-fN";

    @Test
    void reproducesTheRfc8291AppendixAMessageByteForByte() {
        KeyPair applicationServerKeyPair = new KeyPair(
                P256Keys.publicKey(decode(APPLICATION_SERVER_PUBLIC_KEY)),
                P256Keys.privateKey(decode(APPLICATION_SERVER_PRIVATE_KEY)));

        byte[] message = WebPushEncryption.encrypt(PLAINTEXT.getBytes(StandardCharsets.UTF_8),
                decode(USER_AGENT_PUBLIC_KEY), decode(AUTH_SECRET), applicationServerKeyPair, decode(SALT));

        assertThat(BASE64_URL_ENCODER.encodeToString(message)).isEqualTo(MESSAGE);
    }

    @Test
    void writesOneAes128gcmRecordUnderAFreshKeyAndSalt() {
        byte[] userAgentPublicKey = P256Keys.uncompressed((ECPublicKey) P256Keys.generate().getPublic());
        byte[] authSecret = authSecret();
        byte[] plaintext = "{\"version\":1}".getBytes(StandardCharsets.UTF_8);

        byte[] first = WebPushEncryption.encrypt(plaintext, userAgentPublicKey, authSecret);
        byte[] second = WebPushEncryption.encrypt(plaintext, userAgentPublicKey, authSecret);

        ByteBuffer header = ByteBuffer.wrap(first);
        byte[] salt = new byte[16];
        header.get(salt);

        assertThat(header.getInt()).isEqualTo(4096);
        assertThat(header.get()).isEqualTo((byte) 65);
        assertThat(header.get()).isEqualTo((byte) 0x04);
        assertThat(first).hasSize(86 + plaintext.length + 1 + 16);
        assertThat(Arrays.copyOf(second, 16)).isNotEqualTo(salt);
        assertThat(Arrays.copyOfRange(second, 21, 86)).isNotEqualTo(Arrays.copyOfRange(first, 21, 86));
    }

    @Test
    void refusesAPlaintextThatWouldNotFitInOnePushMessage() {
        byte[] userAgentPublicKey = P256Keys.uncompressed((ECPublicKey) P256Keys.generate().getPublic());
        byte[] plaintext = new byte[WebPushEncryption.MAX_PLAINTEXT_LENGTH + 1];

        assertThatThrownBy(() -> WebPushEncryption.encrypt(plaintext, userAgentPublicKey, authSecret()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesAUserAgentKeyThatIsNotOnTheCurve() {
        byte[] offCurve = new byte[65];
        offCurve[0] = 0x04;

        assertThatThrownBy(() -> WebPushEncryption.encrypt(new byte[1], offCurve, authSecret()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] decode(String value) {
        return BASE64_URL_DECODER.decode(value);
    }

    private static byte[] authSecret() {
        byte[] authSecret = new byte[16];
        new SecureRandom().nextBytes(authSecret);

        return authSecret;
    }
}
