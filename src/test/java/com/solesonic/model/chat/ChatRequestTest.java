package com.solesonic.model.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

public class ChatRequestTest {

    private JsonMapper objectMapper;

    @BeforeEach
    public void setup() {
        objectMapper = JsonMapper.builder()
                .changeDefaultPropertyInclusion(incl ->
                        incl.withValueInclusion(JsonInclude.Include.NON_NULL))
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    @Test
    public void testSerializeDeserialize() {
        // Create a ChatRequest instance
        String originalMessage = "Hello, this is a test message";
        ChatRequest originalRequest = new ChatRequest(originalMessage, null);

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(originalRequest);

        // Verify JSON structure
        assertNotNull(json);
        System.out.println("Serialized JSON: " + json);

        // Deserialize back to ChatRequest
        ChatRequest deserializedRequest = objectMapper.readValue(json, ChatRequest.class);

        // Verify the deserialized object
        assertNotNull(deserializedRequest);
        assertEquals(originalMessage, deserializedRequest.chatMessage());
    }

    @Test
    public void testSerializeDeserializeEmptyMessage() {
        // Create a ChatRequest instance with an empty message
        String originalMessage = "";
        ChatRequest originalRequest = new ChatRequest(originalMessage, null);

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(originalRequest);

        // Verify JSON structure
        assertNotNull(json);
        System.out.println("Serialized JSON (empty message): " + json);

        // Deserialize back to ChatRequest
        ChatRequest deserializedRequest = objectMapper.readValue(json, ChatRequest.class);

        // Verify the deserialized object
        assertNotNull(deserializedRequest);
        assertEquals(originalMessage, deserializedRequest.chatMessage());
    }

    @Test
    public void testSerializeDeserializeSpecialCharacters() {
        // Create a ChatRequest instance with special characters
        String originalMessage = "Special characters: !@#$%^&*()_+{}[]|\\:;\"'<>,.?/\nNew line and emoji 😊";
        ChatRequest originalRequest = new ChatRequest(originalMessage, null);

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(originalRequest);

        // Verify JSON structure
        assertNotNull(json);
        System.out.println("Serialized JSON (special characters): " + json);

        // Deserialize back to ChatRequest
        ChatRequest deserializedRequest = objectMapper.readValue(json, ChatRequest.class);

        // Verify the deserialized object
        assertNotNull(deserializedRequest);
        assertEquals(originalMessage, deserializedRequest.chatMessage());
    }

    @Test
    public void testJsonStructure() {
        // Create a ChatRequest instance
        String message = "Test message";
        ChatRequest request = new ChatRequest(message, null);

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(request);

        // Verify JSON structure
        assertNotNull(json);
        System.out.println("JSON structure: " + json);

        // Verify that the JSON contains the expected field
        assertTrue(json.contains("\"chatMessage\""), "JSON should contain 'chatMessage' field");
        assertTrue(json.contains("\"" + message + "\""), "JSON should contain the message value");

        // Verify the JSON format (should be {"chatMessage":"Test message"})
        String expectedJson = "{\"chatMessage\":\"" + message + "\"}";
        assertEquals(expectedJson, json, "JSON structure should match expected format");
    }
}
