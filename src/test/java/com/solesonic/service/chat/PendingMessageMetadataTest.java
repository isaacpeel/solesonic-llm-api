package com.solesonic.service.chat;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class PendingMessageMetadataTest {
    private static final UUID CHAT_ID = UUID.randomUUID();
    private static final UUID OTHER_CHAT_ID = UUID.randomUUID();

    private final PendingMessageMetadata pendingMessageMetadata = new PendingMessageMetadata();

    @Test
    void takeHandsOverWhatWasPutOnce() {
        pendingMessageMetadata.put(CHAT_ID, "solesonic/sound", "cue");
        pendingMessageMetadata.put(CHAT_ID, "other", 1);

        assertThat(pendingMessageMetadata.take(CHAT_ID)).containsOnly(entry("solesonic/sound", "cue"), entry("other", 1));
        assertThat(pendingMessageMetadata.take(CHAT_ID)).isEmpty();
    }

    @Test
    void chatsDoNotShareMetadata() {
        pendingMessageMetadata.put(CHAT_ID, "solesonic/sound", "cue");

        assertThat(pendingMessageMetadata.take(OTHER_CHAT_ID)).isEmpty();
        assertThat(pendingMessageMetadata.take(CHAT_ID)).containsKey("solesonic/sound");
    }

    @Test
    void clearDropsWhatWasPending() {
        pendingMessageMetadata.put(CHAT_ID, "solesonic/sound", "cue");

        pendingMessageMetadata.clear(CHAT_ID);

        assertThat(pendingMessageMetadata.take(CHAT_ID)).isEmpty();
    }
}
