package com.solesonic.model.chat.history;

import com.solesonic.config.JacksonConfig;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A private chat goes out exactly as it did before shared chats, and a shared one carries its extra fields.
 */
class ChatJsonTest {

    private static final JsonMapper JSON_MAPPER = new JacksonConfig().jsonMapper();

    private static JsonNode serialize(Chat chat) {
        return JSON_MAPPER.readTree(JSON_MAPPER.writeValueAsString(chat));
    }

    @Test
    void aPrivateChatHasNoSharedFields() {
        Chat chat = new Chat();
        chat.setId(UUID.randomUUID());

        JsonNode json = serialize(chat);

        assertThat(json.has("sharedSessionId")).isFalse();
        assertThat(json.has("ownerUserId")).isFalse();
        assertThat(json.has("participants")).isFalse();
        assertThat(json.get("kind").asString()).isEqualTo("CONVERSATION");
    }

    @Test
    void aSharedChatCarriesItsSessionOwnerAndSeatedPeople() {
        UUID sessionId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Chat chat = new Chat();
        chat.setKind(ChatKind.SHARED);
        chat.setSharedSessionId(sessionId);
        chat.setOwnerUserId(ownerId);
        chat.setParticipants(List.of(new ChatParticipant(ownerId, "Alex")));

        JsonNode json = serialize(chat);

        assertThat(json.get("sharedSessionId").asString()).isEqualTo(sessionId.toString());
        assertThat(json.get("ownerUserId").asString()).isEqualTo(ownerId.toString());
        assertThat(json.get("participants").get(0).get("name").asString()).isEqualTo("Alex");
    }
}
