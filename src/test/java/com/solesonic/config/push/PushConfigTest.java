package com.solesonic.config.push;

import com.solesonic.service.push.webpush.P256Keys;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every misconfiguration here has to stop startup. Discovering a bad key on the first send would
 * mean every subscription made in between is bound to a key that never worked.
 */
class PushConfigTest {
    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final String SUBJECT = "mailto:operator@example.com";

    private final PushConfig pushConfig = new PushConfig();

    @Test
    void loadsAMatchingKeyPair() {
        KeyPair keyPair = P256Keys.generate();

        VapidKeys vapidKeys = pushConfig.vapidKeys(publicKey(keyPair), privateKey(keyPair), SUBJECT);

        assertThat(vapidKeys.publicKeyBase64Url()).isEqualTo(publicKey(keyPair));
        assertThat(vapidKeys.subject()).isEqualTo(SUBJECT);
    }

    @Test
    void refusesKeysThatAreNotAPair() {
        String privateKey = privateKey(P256Keys.generate());
        String publicKey = publicKey(P256Keys.generate());

        assertThatThrownBy(() -> pushConfig.vapidKeys(publicKey, privateKey, SUBJECT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a key pair")
                .hasMessageNotContaining(privateKey);
    }

    @Test
    void refusesAnUndecodablePublicKey() {
        String privateKey = privateKey(P256Keys.generate());

        assertThatThrownBy(() -> pushConfig.vapidKeys("not base64!", privateKey, SUBJECT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PUSH_VAPID_PUBLIC_KEY");
    }

    @Test
    void refusesAPublicKeyThatIsNotOnTheCurve() {
        byte[] offCurve = new byte[65];
        offCurve[0] = 0x04;
        String privateKey = privateKey(P256Keys.generate());

        assertThatThrownBy(() -> pushConfig.vapidKeys(BASE64_URL_ENCODER.encodeToString(offCurve), privateKey, SUBJECT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PUSH_VAPID_PUBLIC_KEY");
    }

    @Test
    void refusesAPrivateKeyOfTheWrongLengthWithoutEchoingIt() {
        String shortPrivateKey = BASE64_URL_ENCODER.encodeToString(new byte[31]);
        String publicKey = publicKey(P256Keys.generate());

        assertThatThrownBy(() -> pushConfig.vapidKeys(publicKey, shortPrivateKey, SUBJECT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PUSH_VAPID_PRIVATE_KEY")
                .hasMessageNotContaining(shortPrivateKey)
                .hasNoCause();
    }

    @Test
    void refusesAnUndecodablePrivateKey() {
        String publicKey = publicKey(P256Keys.generate());

        assertThatThrownBy(() -> pushConfig.vapidKeys(publicKey, "not base64!", SUBJECT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PUSH_VAPID_PRIVATE_KEY")
                .hasNoCause();
    }

    @Test
    void refusesASubjectThatIsNotAMailtoOrHttpsUri() {
        KeyPair keyPair = P256Keys.generate();

        assertThatThrownBy(() -> pushConfig.vapidKeys(publicKey(keyPair), privateKey(keyPair), "operator@example.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PUSH_VAPID_SUBJECT");
    }

    @Test
    void readsTheSettings() {
        PushSettings pushSettings = pushConfig.pushSettings(true, " fcm.googleapis.com , Push.Example.com ", 3, 5,
                120, 600, 45, 15);

        assertThat(pushSettings.enabled()).isTrue();
        assertThat(pushSettings.allowedEndpointHosts()).containsExactlyInAnyOrder("fcm.googleapis.com", "push.example.com");
        assertThat(pushSettings.maxAttempts()).isEqualTo(3);
        assertThat(pushSettings.maxFailures()).isEqualTo(5);
        assertThat(pushSettings.elicitationReminderDelay()).isEqualTo(Duration.ofSeconds(120));
        assertThat(pushSettings.elicitationTimeout()).isEqualTo(Duration.ofSeconds(600));
        assertThat(pushSettings.presenceTtl()).isEqualTo(Duration.ofSeconds(45));
        assertThat(pushSettings.presenceHeartbeat()).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void refusesAReminderThatIsNotSoonerThanTheElicitationTimeout() {
        assertThatThrownBy(() -> pushConfig.pushSettings(true, "fcm.googleapis.com", 3, 5, 600, 600, 45, 15))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PUSH_ELICITATION_REMINDER_SECONDS");
    }

    @Test
    void refusesAReminderOfZero() {
        assertThatThrownBy(() -> pushConfig.pushSettings(true, "fcm.googleapis.com", 3, 5, 0, 600, 45, 15))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PUSH_ELICITATION_REMINDER_SECONDS");
    }

    @Test
    void refusesAHeartbeatThatIsNotSoonerThanThePresenceTtl() {
        assertThatThrownBy(() -> pushConfig.pushSettings(true, "fcm.googleapis.com", 3, 5, 120, 600, 15, 15))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PUSH_PRESENCE_HEARTBEAT_SECONDS");
    }

    @Test
    void refusesAnEmptyHostAllowlist() {
        assertThatThrownBy(() -> pushConfig.pushSettings(true, " , ", 3, 5, 120, 600, 45, 15))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PUSH_ALLOWED_ENDPOINT_HOSTS");
    }

    @Test
    void refusesFewerThanOneAttempt() {
        assertThatThrownBy(() -> pushConfig.pushSettings(true, "fcm.googleapis.com", 0, 5, 120, 600, 45, 15))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PUSH_MAX_ATTEMPTS");
    }

    @Test
    void refusesFewerThanOneFailure() {
        assertThatThrownBy(() -> pushConfig.pushSettings(true, "fcm.googleapis.com", 3, 0, 120, 600, 45, 15))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PUSH_MAX_FAILURES");
    }

    private static String publicKey(KeyPair keyPair) {
        return BASE64_URL_ENCODER.encodeToString(P256Keys.uncompressed((ECPublicKey) keyPair.getPublic()));
    }

    private static String privateKey(KeyPair keyPair) {
        return BASE64_URL_ENCODER.encodeToString(P256Keys.scalar((ECPrivateKey) keyPair.getPrivate()));
    }
}
